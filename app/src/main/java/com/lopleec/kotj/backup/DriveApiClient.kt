package com.lopleec.kotj.backup

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CancellationException

class DriveApiException(
    val statusCode: Int,
    val reason: String?,
    message: String,
) : IOException(message)

class DriveApiClient {
    /** Deletes every file visible to this app in Drive's private appDataFolder. */
    fun deleteAllAppData(accessToken: String): Int {
        val fileIds = listAppDataFiles(accessToken)
            .mapTo(linkedSetOf()) { file -> file.getString("id") }
        fileIds.forEach { fileId -> deleteFile(accessToken, fileId) }
        return fileIds.size
    }

    fun findLatestBackupSet(accessToken: String): DriveBackupSet = selectLatestBackupSet(listAppDataFiles(accessToken))

    internal fun selectLatestBackupSet(files: List<JSONObject>): DriveBackupSet {
        val backup = selectLatestBackup(files)
        val keyFile = backup?.let { selected ->
            val fingerprint = DriveCloudKeyRecord.fingerprint(selected.fileId)
            files.asSequence()
                .mapNotNull(::parseBackupKeyFile)
                .filter { it.backupFingerprint == fingerprint }
                .maxWithOrNull(compareBy<DriveBackupKeyFile> { it.modifiedAt }.thenBy { it.size })
        }
        return DriveBackupSet(backup, keyFile)
    }

    internal fun selectLatestBackup(files: List<JSONObject>): DriveBackupFile? = files
        .mapNotNull(::parseBackupFile)
        .maxWithOrNull(compareBy<DriveBackupFile> { it.modifiedAt }.thenBy { it.size })

    fun downloadBackup(
        accessToken: String,
        backup: DriveBackupFile,
        destination: File,
    ) {
        require(FILE_ID.matches(backup.fileId)) { "Invalid Drive file ID" }
        require(backup.size in 1..MAX_ENCRYPTED_BACKUP_BYTES) { "Cloud backup exceeds the safety limit" }
        check(!destination.exists() || destination.isFile && destination.length() == 0L) {
            "Backup download target is not empty"
        }
        val connection = openConnection("$DRIVE_FILES_URL/${backup.fileId}?alt=media", "GET", accessToken)
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                readResponse(connection)
                error("Google Drive download failed")
            }
            val advertisedSize = connection.contentLengthLong
            require(advertisedSize <= 0L || advertisedSize <= MAX_ENCRYPTED_BACKUP_BYTES) {
                "Cloud backup exceeds the safety limit"
            }
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var total = 0L
            try {
                connection.inputStream.buffered().use { input ->
                    destination.outputStream().buffered().use { output ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_ENCRYPTED_BACKUP_BYTES) {
                                "Cloud backup exceeds the safety limit"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } finally {
                buffer.fill(0)
            }
            require(total > 0L) { "Downloaded cloud backup is empty" }
            require(total == backup.size) { "Cloud backup changed while it was being downloaded" }
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(destination.toPath()) }
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun downloadBackupKey(accessToken: String, keyFile: DriveBackupKeyFile): ByteArray {
        require(FILE_ID.matches(keyFile.fileId)) { "Invalid Drive key file ID" }
        require(keyFile.size in 1..DriveCloudKeyRecord.MAX_RECORD_BYTES.toLong()) {
            "Cloud backup key exceeds the safety limit"
        }
        val connection = openConnection("$DRIVE_FILES_URL/${keyFile.fileId}?alt=media", "GET", accessToken)
        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                readResponse(connection)
                error("Google Drive key download failed")
            }
            val advertisedSize = connection.contentLengthLong
            require(advertisedSize <= 0L || advertisedSize <= DriveCloudKeyRecord.MAX_RECORD_BYTES.toLong()) {
                "Cloud backup key exceeds the safety limit"
            }
            val bytes = connection.inputStream.use { it.readBounded(DriveCloudKeyRecord.MAX_RECORD_BYTES) }
            try {
                require(bytes.isNotEmpty() && bytes.size.toLong() == keyFile.size) {
                    "Cloud backup key changed while it was being downloaded"
                }
                bytes
            } catch (error: Throwable) {
                bytes.fill(0)
                throw error
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun listAppDataFiles(accessToken: String): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null
        var pageCount = 0
        do {
            check(++pageCount <= MAX_LIST_PAGES) { "Google Drive returned too many app-data pages" }
            val url = buildString {
                append(DRIVE_FILES_URL)
                append("?spaces=appDataFolder&pageSize=").append(LIST_PAGE_SIZE)
                append("&orderBy=modifiedTime%20desc")
                append("&fields=nextPageToken%2Cfiles%28id%2Cname%2CmimeType%2Csize%2CmodifiedTime%2CappProperties%29")
                pageToken?.let { token ->
                    append("&pageToken=").append(URLEncoder.encode(token, Charsets.UTF_8.name()))
                }
            }
            val connection = openConnection(url, "GET", accessToken)
            val response = readResponse(connection)
            val files = response.optJSONArray("files")
            if (files != null) {
                repeat(files.length()) { index ->
                    val file = files.optJSONObject(index) ?: error("Google Drive returned invalid file metadata")
                    val id = file.optString("id")
                    require(FILE_ID.matches(id)) { "Google Drive returned an invalid file ID" }
                    results.add(file)
                    check(results.size <= MAX_APP_DATA_FILES) { "Google Drive app data exceeds the safety limit" }
                }
            }
            pageToken = response.optString("nextPageToken").takeIf(String::isNotBlank)?.also { token ->
                require(token.length <= MAX_PAGE_TOKEN_LENGTH && seenPageTokens.add(token)) {
                    "Google Drive returned an invalid page token"
                }
            }
        } while (pageToken != null)
        return results
    }

    private fun parseBackupFile(file: JSONObject): DriveBackupFile? {
        if (file.optString("name") != BACKUP_FILE_NAME) return null
        val properties = file.optJSONObject("appProperties")
        val recognizedMetadata = file.optString("mimeType") == BACKUP_MIME_TYPE ||
            (properties?.optString("format") == "KOTJ_BACKUP" && properties.optString("version") == "1")
        if (!recognizedMetadata) return null
        val size = file.optString("size").toLongOrNull() ?: return null
        if (size !in 1..MAX_ENCRYPTED_BACKUP_BYTES) return null
        val modifiedAt = runCatching { Instant.parse(file.optString("modifiedTime")).toEpochMilli() }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: return null
        val id = file.optString("id").takeIf(FILE_ID::matches) ?: return null
        return DriveBackupFile(id, size, modifiedAt)
    }

    private fun parseBackupKeyFile(file: JSONObject): DriveBackupKeyFile? {
        if (file.optString("name") != BACKUP_KEY_FILE_NAME) return null
        val properties = file.optJSONObject("appProperties") ?: return null
        val recognizedMetadata = file.optString("mimeType") == BACKUP_KEY_MIME_TYPE &&
            properties.optString("format") == "KOTJ_BACKUP_KEY" &&
            properties.optString("version") == "1"
        if (!recognizedMetadata) return null
        val id = file.optString("id").takeIf(FILE_ID::matches) ?: return null
        val backupFingerprint = properties.optString("backupFingerprint").takeIf(BACKUP_FINGERPRINT::matches)
            ?: return null
        val size = file.optString("size").toLongOrNull() ?: return null
        if (size !in 1..DriveCloudKeyRecord.MAX_RECORD_BYTES.toLong()) return null
        val modifiedAt = runCatching { Instant.parse(file.optString("modifiedTime")).toEpochMilli() }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: return null
        return DriveBackupKeyFile(id, backupFingerprint, size, modifiedAt)
    }

    fun uploadBackup(
        accessToken: String,
        encryptedBackup: File,
        knownFileId: String?,
        shouldContinue: () -> Boolean = { true },
    ): DriveUploadResult {
        require(encryptedBackup.isFile && encryptedBackup.length() > 0L) { "Encrypted backup is empty" }
        ensureUploadCanContinue(shouldContinue)
        val metadata = AppDataUploadMetadata(
            name = BACKUP_FILE_NAME,
            mimeType = BACKUP_MIME_TYPE,
            properties = mapOf("format" to "KOTJ_BACKUP", "version" to "1"),
        )
        // A missing local file ID means this installation does not own an existing remote backup.
        // Create a new appDataFolder file instead of guessing by name and potentially overwriting
        // a backup left by another installation.
        var fileId = knownFileId?.takeIf(FILE_ID::matches)

        fun startSession(): String {
            ensureUploadCanContinue(shouldContinue)
            val currentFileId = fileId ?: return startResumableUpload(
                accessToken,
                encryptedBackup.length(),
                metadata,
                null,
            )
            return try {
                startResumableUpload(accessToken, encryptedBackup.length(), metadata, currentFileId)
            } catch (error: DriveApiException) {
                if (error.statusCode != HttpURLConnection.HTTP_NOT_FOUND) throw error
                // appDataFolder files may be manually deleted by the user. Do not keep retrying a
                // stale ID; create a new private backup file for this installation.
                fileId = null
                startResumableUpload(accessToken, encryptedBackup.length(), metadata, null)
            }
        }

        var session = startSession()
        repeat(MAX_SESSION_ATTEMPTS) { attempt ->
            try {
                return uploadResumableContent(
                    accessToken = accessToken,
                    sessionLocation = session,
                    backup = encryptedBackup,
                    mimeType = BACKUP_MIME_TYPE,
                    shouldContinue = shouldContinue,
                )
            } catch (error: DriveApiException) {
                if (error.statusCode != HttpURLConnection.HTTP_NOT_FOUND || attempt == MAX_SESSION_ATTEMPTS - 1) {
                    throw error
                }
                // Google documents 404 as an expired resumable-upload session. Start a fresh
                // session once; all other retry decisions remain with WorkManager.
                session = startSession()
            }
        }
        error("Google Drive upload did not complete")
    }

    fun uploadBackupKey(
        accessToken: String,
        keyRecord: ByteArray,
        backupFileId: String,
        knownFileId: String?,
        shouldContinue: () -> Boolean = { true },
    ): DriveUploadResult {
        require(FILE_ID.matches(backupFileId)) { "Invalid backup file ID" }
        require(keyRecord.size in 1..DriveCloudKeyRecord.MAX_RECORD_BYTES) { "Invalid cloud backup key record" }
        ensureUploadCanContinue(shouldContinue)
        val metadata = AppDataUploadMetadata(
            name = BACKUP_KEY_FILE_NAME,
            mimeType = BACKUP_KEY_MIME_TYPE,
            properties = mapOf(
                "format" to "KOTJ_BACKUP_KEY",
                "version" to "1",
                "backupFingerprint" to DriveCloudKeyRecord.fingerprint(backupFileId),
            ),
        )
        var fileId = knownFileId?.takeIf(FILE_ID::matches)

        fun startSession(): String {
            ensureUploadCanContinue(shouldContinue)
            val currentFileId = fileId ?: return startResumableUpload(
                accessToken,
                keyRecord.size.toLong(),
                metadata,
                null,
            )
            return try {
                startResumableUpload(accessToken, keyRecord.size.toLong(), metadata, currentFileId)
            } catch (error: DriveApiException) {
                if (error.statusCode != HttpURLConnection.HTTP_NOT_FOUND) throw error
                fileId = null
                startResumableUpload(accessToken, keyRecord.size.toLong(), metadata, null)
            }
        }

        var session = startSession()
        repeat(MAX_SESSION_ATTEMPTS) { attempt ->
            try {
                return uploadResumableBytes(
                    accessToken = accessToken,
                    sessionLocation = session,
                    bytes = keyRecord,
                    mimeType = BACKUP_KEY_MIME_TYPE,
                    shouldContinue = shouldContinue,
                )
            } catch (error: DriveApiException) {
                if (error.statusCode != HttpURLConnection.HTTP_NOT_FOUND || attempt == MAX_SESSION_ATTEMPTS - 1) {
                    throw error
                }
                session = startSession()
            }
        }
        error("Google Drive key upload did not complete")
    }

    private fun startResumableUpload(
        accessToken: String,
        contentLength: Long,
        metadata: AppDataUploadMetadata,
        fileId: String?,
    ): String {
        require(contentLength > 0L) { "Upload content is empty" }
        val updating = fileId != null
        if (updating) require(FILE_ID.matches(requireNotNull(fileId))) { "Invalid Drive file ID" }
        val url = if (updating) {
            "$DRIVE_UPLOAD_URL/$fileId?uploadType=resumable&fields=id,size,modifiedTime"
        } else {
            "$DRIVE_UPLOAD_URL?uploadType=resumable&fields=id,size,modifiedTime"
        }
        val metadataBytes = JSONObject().apply {
            put("name", metadata.name)
            put("mimeType", metadata.mimeType)
            put("appProperties", JSONObject().apply {
                metadata.properties.forEach { (key, value) -> put(key, value) }
            })
            if (!updating) put("parents", org.json.JSONArray().put("appDataFolder"))
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = openConnection(url, "POST", accessToken).apply {
            doOutput = true
            if (updating) {
                // Android's HttpURLConnection does not consistently expose PATCH. Google APIs
                // accept the method override for the Drive files.update initiation request.
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", metadata.mimeType)
            setRequestProperty("X-Upload-Content-Length", contentLength.toString())
            setFixedLengthStreamingMode(metadataBytes.size)
        }
        return try {
            connection.outputStream.use { it.write(metadataBytes) }
            val sessionLocation = connection.getHeaderField("Location")
            readResponse(connection)
            validateSessionLocation(sessionLocation)
        } finally {
            metadataBytes.fill(0)
            connection.disconnect()
        }
    }

    private fun deleteFile(accessToken: String, fileId: String) {
        require(FILE_ID.matches(fileId)) { "Invalid Drive file ID" }
        val connection = openConnection("$DRIVE_FILES_URL/$fileId", "DELETE", accessToken)
        try {
            readResponse(connection)
        } catch (error: DriveApiException) {
            // A file removed between list and delete already satisfies the user's request.
            if (error.statusCode != HttpURLConnection.HTTP_NOT_FOUND) throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadResumableContent(
        accessToken: String,
        sessionLocation: String,
        backup: File,
        mimeType: String,
        shouldContinue: () -> Boolean,
    ): DriveUploadResult {
        val totalBytes = backup.length()
        val buffer = ByteArray(RESUMABLE_CHUNK_BYTES)
        var offset = 0L
        var stalledResponses = 0
        try {
            RandomAccessFile(backup, "r").use { input ->
                while (offset < totalBytes) {
                    ensureUploadCanContinue(shouldContinue)
                    val count = minOf(buffer.size.toLong(), totalBytes - offset).toInt()
                    input.seek(offset)
                    input.readFully(buffer, 0, count)
                    val progress = try {
                        uploadChunk(accessToken, sessionLocation, buffer, 0, count, offset, totalBytes, mimeType)
                    } catch (error: DriveApiException) {
                        if (error.statusCode !in 500..599) throw error
                        queryUploadProgress(accessToken, sessionLocation, totalBytes)
                    } catch (_: IOException) {
                        queryUploadProgress(accessToken, sessionLocation, totalBytes)
                    }
                    when (progress) {
                        is UploadProgress.Complete -> return progress.result
                        is UploadProgress.Incomplete -> {
                            require(progress.nextOffset in 0..totalBytes) { "Invalid resumable upload position" }
                            if (progress.nextOffset <= offset) {
                                stalledResponses++
                                if (stalledResponses >= MAX_STALLED_RESPONSES) {
                                    throw IOException("Google Drive upload made no progress")
                                }
                            } else {
                                stalledResponses = 0
                            }
                            offset = progress.nextOffset
                        }
                    }
                }
            }
            ensureUploadCanContinue(shouldContinue)
            return when (val progress = queryUploadProgress(accessToken, sessionLocation, totalBytes)) {
                is UploadProgress.Complete -> progress.result
                is UploadProgress.Incomplete -> throw IOException("Google Drive upload did not complete")
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun uploadResumableBytes(
        accessToken: String,
        sessionLocation: String,
        bytes: ByteArray,
        mimeType: String,
        shouldContinue: () -> Boolean,
    ): DriveUploadResult {
        val totalBytes = bytes.size.toLong()
        var offset = 0L
        var stalledResponses = 0
        while (offset < totalBytes) {
            ensureUploadCanContinue(shouldContinue)
            val count = (totalBytes - offset).toInt()
            val progress = try {
                uploadChunk(
                    accessToken,
                    sessionLocation,
                    bytes,
                    offset.toInt(),
                    count,
                    offset,
                    totalBytes,
                    mimeType,
                )
            } catch (error: DriveApiException) {
                if (error.statusCode !in 500..599) throw error
                queryUploadProgress(accessToken, sessionLocation, totalBytes)
            } catch (_: IOException) {
                queryUploadProgress(accessToken, sessionLocation, totalBytes)
            }
            when (progress) {
                is UploadProgress.Complete -> return progress.result
                is UploadProgress.Incomplete -> {
                    require(progress.nextOffset in 0..totalBytes) { "Invalid resumable upload position" }
                    if (progress.nextOffset <= offset) {
                        stalledResponses++
                        if (stalledResponses >= MAX_STALLED_RESPONSES) {
                            throw IOException("Google Drive key upload made no progress")
                        }
                    } else {
                        stalledResponses = 0
                    }
                    offset = progress.nextOffset
                }
            }
        }
        ensureUploadCanContinue(shouldContinue)
        return when (val progress = queryUploadProgress(accessToken, sessionLocation, totalBytes)) {
            is UploadProgress.Complete -> progress.result
            is UploadProgress.Incomplete -> throw IOException("Google Drive key upload did not complete")
        }
    }

    private fun uploadChunk(
        accessToken: String,
        sessionLocation: String,
        bytes: ByteArray,
        byteArrayOffset: Int,
        count: Int,
        offset: Long,
        totalBytes: Long,
        mimeType: String,
    ): UploadProgress {
        val connection = openConnection(sessionLocation, "PUT", accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", mimeType)
            setRequestProperty("Content-Range", "bytes $offset-${offset + count - 1}/$totalBytes")
            setFixedLengthStreamingMode(count)
        }
        return try {
            connection.outputStream.use { it.write(bytes, byteArrayOffset, count) }
            readUploadProgress(connection, totalBytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun queryUploadProgress(
        accessToken: String,
        sessionLocation: String,
        totalBytes: Long,
    ): UploadProgress {
        val connection = openConnection(sessionLocation, "PUT", accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Length", "0")
            setRequestProperty("Content-Range", "bytes */$totalBytes")
            setFixedLengthStreamingMode(0)
        }
        return try {
            connection.outputStream.close()
            readUploadProgress(connection, totalBytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun readUploadProgress(connection: HttpURLConnection, fallbackSize: Long): UploadProgress {
        if (connection.responseCode == HTTP_RESUME_INCOMPLETE) {
            val nextOffset = connection.getHeaderField("Range")
                ?.let { range -> UPLOAD_RANGE.matchEntire(range)?.groupValues?.get(1)?.toLongOrNull() }
                ?.plus(1L)
                ?: 0L
            return UploadProgress.Incomplete(nextOffset)
        }
        val response = readResponse(connection)
        return UploadProgress.Complete(parseUploadResponse(response, fallbackSize))
    }

    internal fun parseUploadResponse(response: JSONObject, fallbackSize: Long): DriveUploadResult {
        val id = response.optString("id")
        require(FILE_ID.matches(id)) { "Google Drive did not return a valid file ID" }
        val size = response.optString("size").toLongOrNull() ?: fallbackSize
        require(size == fallbackSize) { "Google Drive reported an unexpected uploaded file size" }
        val modifiedAt = runCatching { Instant.parse(response.optString("modifiedTime")).toEpochMilli() }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: error("Google Drive did not return a valid modification time")
        return DriveUploadResult(id, size, modifiedAt)
    }

    private fun validateSessionLocation(value: String?): String {
        val session = requireNotNull(value) { "Google Drive did not return an upload session" }
        val uri = URI(session)
        require(uri.isAllowedGoogleEndpoint()) {
            "Google Drive returned an unexpected upload endpoint"
        }
        return uri.toASCIIString()
    }

    private fun openConnection(url: String, method: String, accessToken: String): HttpURLConnection {
        require(accessToken.isNotBlank()) { "Missing Google authorization token" }
        val uri = URI(url)
        require(uri.isAllowedGoogleEndpoint()) { "Refusing an unexpected Google API endpoint" }
        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        return try {
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBounded(MAX_RESPONSE_BYTES) }
                ?: ByteArray(0)
            val body = bytes.toString(Charsets.UTF_8)
            bytes.fill(0)
            if (status !in 200..299) {
                val apiError = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
                val apiMessage = apiError?.optString("message")?.takeIf(String::isNotBlank)
                val apiReason = apiError
                    ?.optJSONArray("errors")
                    ?.optJSONObject(0)
                    ?.optString("reason")
                    ?.takeIf(String::isNotBlank)
                throw DriveApiException(status, apiReason, apiMessage ?: "Google Drive request failed ($status)")
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, RESPONSE_BUFFER_BYTES))
        val buffer = ByteArray(RESPONSE_BUFFER_BYTES)
        var total = 0
        try {
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IOException("Google API response exceeded the safety limit")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private fun ensureUploadCanContinue(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) throw CancellationException("Google Drive backup was cancelled")
    }

    private fun URI.isAllowedGoogleEndpoint(): Boolean =
        scheme == "https" && host in ALLOWED_HOSTS && userInfo == null && (port == -1 || port == 443)

    companion object {
        const val BACKUP_FILE_NAME = "kotj-backup-v1.kbak"
        const val BACKUP_KEY_FILE_NAME = "kotj-backup-key-v1.bin"
        private const val BACKUP_MIME_TYPE = "application/vnd.com.lopleec.kotj.backup"
        private const val BACKUP_KEY_MIME_TYPE = "application/vnd.com.lopleec.kotj.backup-key"
        private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val RESPONSE_BUFFER_BYTES = 16 * 1024
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val RESUMABLE_CHUNK_BYTES = 8 * 1024 * 1024
        private const val MAX_ENCRYPTED_BACKUP_BYTES = 1088L * 1024L * 1024L
        private const val HTTP_RESUME_INCOMPLETE = 308
        private const val MAX_STALLED_RESPONSES = 3
        private const val MAX_SESSION_ATTEMPTS = 2
        private const val LIST_PAGE_SIZE = 100
        private const val MAX_LIST_PAGES = 100
        private const val MAX_APP_DATA_FILES = LIST_PAGE_SIZE * MAX_LIST_PAGES
        private const val MAX_PAGE_TOKEN_LENGTH = 4_096
        private val FILE_ID = Regex("[A-Za-z0-9_-]{10,200}")
        private val BACKUP_FINGERPRINT = Regex("[0-9a-f]{64}")
        private val UPLOAD_RANGE = Regex("bytes=0-(\\d+)")
        private val ALLOWED_HOSTS = setOf("www.googleapis.com")
    }

    private sealed interface UploadProgress {
        data class Incomplete(val nextOffset: Long) : UploadProgress
        data class Complete(val result: DriveUploadResult) : UploadProgress
    }

    private data class AppDataUploadMetadata(
        val name: String,
        val mimeType: String,
        val properties: Map<String, String>,
    )
}
