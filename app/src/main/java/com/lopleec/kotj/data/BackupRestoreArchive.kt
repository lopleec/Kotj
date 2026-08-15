package com.lopleec.kotj.data

import com.lopleec.kotj.model.BlockType
import com.lopleec.kotj.model.NoteDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipInputStream

internal data class RestoredCategory(
    val id: String,
    val name: String,
    val createdAt: Long,
)

internal data class RestoredNote(
    val id: String,
    val categoryId: String?,
    val payload: String,
    val encrypted: Boolean,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val pinned: Boolean,
    val document: NoteDocument?,
)

internal data class RestoredArchive(
    val createdAt: Long,
    val categories: List<RestoredCategory>,
    val notes: List<RestoredNote>,
    val attachmentCount: Int,
)

internal object BackupRestoreArchive {
    fun extract(input: InputStream, stagingDirectory: File): RestoredArchive {
        check(!stagingDirectory.exists() && stagingDirectory.mkdirs()) {
            "Could not create the restore workspace"
        }
        return try {
            ZipInputStream(input.buffered()).use { zip ->
                val manifestEntry = requireNotNull(zip.nextEntry) { "Backup archive has no manifest" }
                require(!manifestEntry.isDirectory && manifestEntry.name == MANIFEST_ENTRY) {
                    "Backup manifest must be the first archive entry"
                }
                val manifestBytes = zip.readBounded(MAX_MANIFEST_BYTES)
                val archive = try {
                    parseManifest(manifestBytes.toString(Charsets.UTF_8))
                } finally {
                    manifestBytes.fill(0)
                }
                zip.closeEntry()

                val expectedAttachments = archive.expectedAttachments
                val extracted = mutableSetOf<String>()
                var totalBytes = 0L
                var entryCount = 1
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++entryCount <= MAX_ARCHIVE_ENTRIES) { "Backup archive has too many entries" }
                    require(!entry.isDirectory && entry.name.startsWith(ATTACHMENT_PREFIX)) {
                        "Backup archive contains an unsupported entry"
                    }
                    val fileName = entry.name.removePrefix(ATTACHMENT_PREFIX)
                    require(ATTACHMENT_FILE_NAME.matches(fileName)) { "Backup contains an invalid attachment name" }
                    val expectedSize = requireNotNull(expectedAttachments[fileName]) {
                        "Backup contains an undeclared attachment"
                    }
                    require(extracted.add(fileName)) { "Backup contains a duplicate attachment" }
                    if (entry.size >= 0L) {
                        require(entry.size == expectedSize) { "Backup attachment size does not match its manifest" }
                    }
                    val target = File(stagingDirectory, fileName)
                    require(target.parentFile?.canonicalFile == stagingDirectory.canonicalFile) {
                        "Backup attachment escaped the restore workspace"
                    }
                    val copied = target.outputStream().buffered().use { output ->
                        zip.copyBounded(output, expectedSize)
                    }
                    require(copied == expectedSize) { "Backup attachment is truncated" }
                    totalBytes += copied
                    require(totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES) { "Backup attachments exceed the safety limit" }
                    zip.closeEntry()
                }
                require(extracted == expectedAttachments.keys) { "Backup is missing one or more attachments" }
                RestoredArchive(
                    createdAt = archive.createdAt,
                    categories = archive.categories,
                    notes = archive.notes,
                    attachmentCount = extracted.size,
                )
            }
        } catch (error: Throwable) {
            wipeDirectory(stagingDirectory)
            throw error
        }
    }

    private fun parseManifest(json: String): ParsedManifest {
        val root = JSONObject(json)
        require(root.optString("format") == "kotj-drive-backup") { "Not a Kotj Drive backup" }
        require(root.optInt("version") == 1) { "Unsupported Kotj backup version" }
        require(root.optInt("databaseVersion") in 1..2) { "Unsupported Kotj database backup version" }
        require(root.optString("storageMode") in DriveStorageMode.entries.map(DriveStorageMode::name)) {
            "Backup has an invalid storage mode"
        }
        val createdAt = root.optLong("createdAt").takeIf { it > 0L }
            ?: error("Backup has an invalid creation time")

        val categoriesJson = requireNotNull(root.optJSONArray("categories")) {
            "Backup manifest is missing its groups"
        }
        require(categoriesJson.length() <= MAX_CATEGORIES) { "Backup contains too many groups" }
        val categoryIds = mutableSetOf<String>()
        val categories = List(categoriesJson.length()) { index ->
            val item = categoriesJson.getJSONObject(index)
            val id = item.getString("id").validatedIdentifier("group")
            require(categoryIds.add(id)) { "Backup contains duplicate groups" }
            val name = item.getString("name")
            require(name.isNotBlank() && name.length <= MAX_CATEGORY_NAME_LENGTH) {
                "Backup contains an invalid group name"
            }
            RestoredCategory(
                id = id,
                name = name,
                createdAt = item.getLong("createdAt").also { require(it > 0L) },
            )
        }

        val notesJson = requireNotNull(root.optJSONArray("notes")) {
            "Backup manifest is missing its notes"
        }
        require(notesJson.length() <= MAX_NOTES) { "Backup contains too many notes" }
        val noteIds = mutableSetOf<String>()
        val notes = List(notesJson.length()) { index ->
            val item = notesJson.getJSONObject(index)
            val id = item.getString("id")
            require(NOTE_ID.matches(id) && noteIds.add(id)) { "Backup contains an invalid or duplicate note ID" }
            val categoryId = item.nullableString("categoryId")?.also {
                require(it in categoryIds) { "Backup note refers to a missing group" }
            }
            val payload = item.getString("payload")
            require(payload.isNotEmpty()) { "Backup contains an empty note payload" }
            val encrypted = item.getBoolean("encrypted")
            val document = if (encrypted) {
                require(payload.startsWith("KOTJ1:")) { "Backup contains invalid encrypted note data" }
                null
            } else {
                NoteJson.decode(payload)
            }
            val deleted = item.getBoolean("deleted")
            val deletedAt = item.nullableLong("deletedAt")
            require((deleted && deletedAt != null && deletedAt > 0L) || (!deleted && deletedAt == null)) {
                "Backup contains an invalid deletion time"
            }
            RestoredNote(
                id = id,
                categoryId = categoryId,
                payload = payload,
                encrypted = encrypted,
                deleted = deleted,
                createdAt = item.getLong("createdAt").also { require(it > 0L) },
                updatedAt = item.getLong("updatedAt").also { require(it > 0L) },
                deletedAt = deletedAt,
                pinned = item.optBoolean("pinned", false),
                document = document,
            )
        }

        val attachmentsJson = requireNotNull(root.optJSONArray("attachments")) {
            "Backup manifest is missing its attachments"
        }
        require(attachmentsJson.length() <= MAX_ATTACHMENTS) { "Backup contains too many attachments" }
        var totalAttachmentBytes = 0L
        val expectedAttachments = buildMap {
            repeat(attachmentsJson.length()) { index ->
                val item = attachmentsJson.getJSONObject(index)
                val name = item.getString("name")
                require(ATTACHMENT_FILE_NAME.matches(name)) { "Backup contains an invalid attachment name" }
                require(name.take(NOTE_ID_LENGTH) in noteIds) { "Backup attachment belongs to a missing note" }
                val size = item.getLong("size")
                require(size in 0..MAX_STORED_ATTACHMENT_BYTES) { "Backup attachment exceeds the safety limit" }
                require(put(name, size) == null) { "Backup contains duplicate attachment metadata" }
                totalAttachmentBytes += size
                require(totalAttachmentBytes <= MAX_TOTAL_ATTACHMENT_BYTES) {
                    "Backup attachments exceed the safety limit"
                }
            }
        }

        notes.asSequence()
            .mapNotNull(RestoredNote::document)
            .flatMap { document -> document.blocks.asSequence() }
            .filter { it.type == BlockType.IMAGE }
            .mapNotNull { block ->
                block.imageUri?.takeIf { it.startsWith(INTERNAL_ATTACHMENT_PREFIX) }
                    ?.removePrefix(INTERNAL_ATTACHMENT_PREFIX)
            }
            .forEach { name ->
                require(name in expectedAttachments) { "Backup is missing an attachment used by a note" }
            }

        return ParsedManifest(createdAt, categories, notes, expectedAttachments)
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, BUFFER_BYTES))
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        try {
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("Backup manifest exceeds the safety limit")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private fun InputStream.copyBounded(output: java.io.OutputStream, expectedBytes: Long): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        try {
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                total += count
                if (total > expectedBytes) throw IOException("Backup attachment is larger than declared")
                output.write(buffer, 0, count)
            }
            return total
        } finally {
            buffer.fill(0)
        }
    }

    private fun String.validatedIdentifier(label: String): String = also {
        require(length in 1..MAX_IDENTIFIER_LENGTH && none(Char::isISOControl)) {
            "Backup contains an invalid $label ID"
        }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.nullableLong(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private fun wipeDirectory(directory: File) {
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { file ->
            if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                runCatching {
                    RandomAccessFile(file, "rws").use { output ->
                        val zeros = ByteArray(BUFFER_BYTES)
                        try {
                            var remaining = output.length()
                            output.seek(0)
                            while (remaining > 0L) {
                                val count = minOf(remaining, zeros.size.toLong()).toInt()
                                output.write(zeros, 0, count)
                                remaining -= count
                            }
                            output.fd.sync()
                        } finally {
                            zeros.fill(0)
                        }
                    }
                }
            }
            runCatching { file.delete() }
        }
    }

    private data class ParsedManifest(
        val createdAt: Long,
        val categories: List<RestoredCategory>,
        val notes: List<RestoredNote>,
        val expectedAttachments: Map<String, Long>,
    )

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val ATTACHMENT_PREFIX = "attachments/"
    private const val INTERNAL_ATTACHMENT_PREFIX = "kotj-attachment:"
    private const val NOTE_ID_LENGTH = 36
    private const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
    private const val MAX_CATEGORIES = 10_000
    private const val MAX_NOTES = 100_000
    private const val MAX_ATTACHMENTS = 50_000
    private const val MAX_ARCHIVE_ENTRIES = MAX_ATTACHMENTS + 1
    private const val MAX_IDENTIFIER_LENGTH = 200
    private const val MAX_CATEGORY_NAME_LENGTH = 4_096
    private const val MAX_STORED_ATTACHMENT_BYTES = 26L * 1024L * 1024L
    private const val MAX_TOTAL_ATTACHMENT_BYTES = 1024L * 1024L * 1024L
    private const val BUFFER_BYTES = 64 * 1024
    private val NOTE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val ATTACHMENT_FILE_NAME = Regex("${NOTE_ID.pattern}-${NOTE_ID.pattern}")
}
