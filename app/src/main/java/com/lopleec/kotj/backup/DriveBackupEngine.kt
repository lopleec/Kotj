package com.lopleec.kotj.backup

import android.content.Context
import com.lopleec.kotj.data.NotesRepository
import com.lopleec.kotj.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

class DriveBackupEngine(context: Context) {
    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val preferences = DriveBackupPreferences(appContext)
    private val keyStore = DriveBackupKeyStore(appContext)
    private val repository = NotesRepository(appContext)
    private val api = DriveApiClient()

    fun state(backupInProgress: Boolean = false): DriveBackupUiState =
        preferences.load(keyStore.hasKey()).copy(backupInProgress = backupInProgress)

    fun accountEmail(): String? = preferences.accountEmail()

    fun clearCloudConfiguration(): DriveBackupUiState {
        keyStore.clear()
        preferences.clearAccount()
        return state()
    }

    fun markAuthorizationRequired(): DriveBackupUiState {
        preferences.markAuthorizationRequired()
        return state()
    }

    /** Removes plaintext restore ZIPs left only if the process stopped between decrypt and cleanup. */
    fun cleanupAbandonedRestoreWorkspace() {
        val restoreDirectory = File(appContext.cacheDir, "drive-restore")
        if (restoreDirectory.isDirectory) cleanupAbandonedRestoreFiles(restoreDirectory)
    }

    suspend fun completeAuthorization(
        accessToken: String,
        accountEmail: String,
    ): DriveBackupUiState = withContext(Dispatchers.IO) {
        BACKUP_MUTEX.withLock {
            if (!settingsRepository.isGoogleDriveBackupEnabled()) return@withLock state()
            val cleanEmail = accountEmail.trim()
            require(cleanEmail.isNotEmpty()) { "Google Account email was not returned" }
            val accountChanged = !preferences.accountEmail().equals(cleanEmail, ignoreCase = true)
            val hadLocalKey = keyStore.hasKey()
            val locallyOwnedBackupId = preferences.driveFileId().takeUnless { accountChanged }
            val lastSeenRemoteModifiedAt = preferences.remoteBackupModifiedAt().takeUnless { accountChanged }
            val restoreWasAlreadyRequired = preferences.restoreRequired() && !accountChanged
            // Read the selected account first. A cancelled picker or failed network request must
            // leave the previously connected account fully usable.
            val remote = api.findLatestBackupSet(accessToken)
            val user = GoogleUserInfo(displayName = null, email = cleanEmail)
            if (accountChanged) preferences.replaceAccount(user) else preferences.saveAccount(user)
            val restoreRequired = requiresCloudMerge(
                remote = remote,
                localBackupId = locallyOwnedBackupId,
                hasLocalKey = hadLocalKey,
                lastSeenRemoteModifiedAt = lastSeenRemoteModifiedAt,
                mergeWasAlreadyRequired = restoreWasAlreadyRequired,
            )
            val installationOwnsRemote = remote.backup != null && !restoreRequired
            preferences.saveRemoteBackup(remote, restoreRequired)

            when {
                remote.backup == null -> {
                    if (!hadLocalKey) keyStore.generateRandomKey()
                    performBackupLocked(accessToken)
                }
                installationOwnsRemote -> {
                    // Also migrates password-era backups by publishing their already-derived key
                    // into this account's private appDataFolder after a fresh encrypted upload.
                    performBackupLocked(accessToken)
                }
                else -> state()
            }
        }
    }

    suspend fun restoreLatestBackup(
        accessToken: String,
        accountEmail: String,
    ): Pair<DriveBackupUiState, DriveRestoreResult> = withContext(Dispatchers.IO) {
        BACKUP_MUTEX.withLock {
            currentCoroutineContext().ensureActive()
            require(settingsRepository.isGoogleDriveBackupEnabled()) { "Google Drive backup is disabled" }
            val cleanEmail = accountEmail.trim()
            require(cleanEmail.isNotEmpty()) { "Google Account email was not returned" }
            preferences.saveAccount(GoogleUserInfo(displayName = null, email = cleanEmail))
            val remote = api.findLatestBackupSet(accessToken)
            preferences.saveRemoteBackup(remote, restoreRequired = remote.backup != null)
            val backup = requireNotNull(remote.backup) {
                "No Kotj backup was found in this Google Account"
            }
            val keyFile = requireNotNull(remote.keyFile) {
                "This backup has not been migrated to Google Account recovery"
            }
            val restoreDirectory = File(appContext.cacheDir, "drive-restore")
            check(restoreDirectory.exists() || restoreDirectory.mkdirs()) {
                "Could not create the restore workspace"
            }
            cleanupAbandonedRestoreFiles(restoreDirectory)
            val encrypted = File.createTempFile("kotj-restore-", ".kbak", restoreDirectory)
            val plaintext = File.createTempFile("kotj-restore-", ".zip", restoreDirectory)
            var header: BackupFileCipher.Header? = null
            var keyRecord: ByteArray? = null
            var material: BackupKeyMaterial? = null
            try {
                val downloadedKeyRecord = api.downloadBackupKey(accessToken, keyFile)
                keyRecord = downloadedKeyRecord
                val decodedMaterial = DriveCloudKeyRecord.decode(downloadedKeyRecord, backup.fileId)
                material = decodedMaterial
                api.downloadBackup(accessToken, backup, encrypted)
                val parsedHeader = encrypted.inputStream().buffered().use(BackupFileCipher::readHeader)
                header = parsedHeader
                require(MessageDigest.isEqual(decodedMaterial.salt, parsedHeader.salt)) {
                    "Cloud backup key does not match the encrypted backup"
                }
                try {
                    encrypted.inputStream().buffered().use { input ->
                        plaintext.outputStream().buffered().use { output ->
                            BackupFileCipher.decrypt(input, output, decodedMaterial.keyBytes)
                        }
                    }
                } catch (error: AEADBadTagException) {
                    throw IllegalArgumentException("Cloud backup or its account key is damaged", error)
                }
                currentCoroutineContext().ensureActive()
                // Persist the portable key before changing local notes. restoreRequired remains set
                // until the repository transaction commits, so background work cannot overwrite
                // the cloud backup if the process stops between these operations.
                keyStore.storeKeyMaterial(decodedMaterial.keyBytes, decodedMaterial.salt)
                val result = plaintext.inputStream().buffered().use(repository::restoreBackupZip)
                preferences.markRestoreSucceeded(remote)
                state() to result
            } finally {
                keyRecord?.fill(0)
                material?.keyBytes?.fill(0)
                material?.salt?.fill(0)
                header?.salt?.fill(0)
                header?.iv?.fill(0)
                header?.encoded?.fill(0)
                runCatching { Files.deleteIfExists(encrypted.toPath()) }
                if (plaintext.exists()) runCatching { securelyDeleteFile(plaintext) }
            }
        }
    }

    suspend fun performBackup(
        accessToken: String,
        shouldContinue: () -> Boolean = { true },
    ): DriveBackupUiState = withContext(Dispatchers.IO) {
        BACKUP_MUTEX.withLock {
            performBackupLocked(accessToken, shouldContinue)
        }
    }

    private suspend fun performBackupLocked(
        accessToken: String,
        shouldContinue: () -> Boolean = { true },
    ): DriveBackupUiState {
        currentCoroutineContext().ensureActive()
        val settings = settingsRepository.load()
        if (!settings.googleDriveBackupEnabled || !shouldContinue() || preferences.restoreRequired()) return state()
        val material = keyStore.load()
        var temporary: File? = null
        var keyRecord: ByteArray? = null
        try {
            // Never let an older device overwrite a snapshot that another installation updated.
            // The user first merges that remote revision; the resulting union is then uploaded.
            val remote = api.findLatestBackupSet(accessToken)
            if (
                requiresMergeBeforeUpload(
                    remote = remote,
                    localBackupId = preferences.driveFileId(),
                    lastSeenRemoteModifiedAt = preferences.remoteBackupModifiedAt(),
                )
            ) {
                preferences.saveRemoteBackup(remote, restoreRequired = true)
                return state()
            }
            preferences.saveRemoteBackup(remote, restoreRequired = false)
            val backupDirectory = File(appContext.cacheDir, "drive-backup")
            check(backupDirectory.exists() || backupDirectory.mkdirs()) {
                "Could not create backup workspace"
            }
            cleanupAbandonedTemporaryBackups(backupDirectory)
            temporary = File.createTempFile("kotj-", ".kbak", backupDirectory)
            temporary.outputStream().buffered().use { output ->
                BackupFileCipher.encrypt(output, material.keyBytes, material.salt) { encryptedOutput ->
                    repository.writeBackupZip(encryptedOutput, settings.driveStorageMode)
                }
            }
            currentCoroutineContext().ensureActive()
            if (!settingsRepository.isGoogleDriveBackupEnabled() || !shouldContinue() || preferences.restoreRequired()) {
                return state()
            }
            val continueUpload = {
                settingsRepository.isGoogleDriveBackupEnabled() &&
                    !preferences.restoreRequired() && shouldContinue()
            }
            val backupResult = api.uploadBackup(
                accessToken = accessToken,
                encryptedBackup = temporary,
                knownFileId = preferences.driveFileId(),
                shouldContinue = continueUpload,
            )
            // Save a newly-created ID immediately. If key publication fails, the retry updates this
            // file instead of creating duplicate snapshots.
            preferences.savePendingBackup(backupResult)
            val encodedKeyRecord = DriveCloudKeyRecord.encode(material, backupResult.fileId)
            keyRecord = encodedKeyRecord
            val keyResult = api.uploadBackupKey(
                accessToken = accessToken,
                keyRecord = encodedKeyRecord,
                backupFileId = backupResult.fileId,
                knownFileId = preferences.driveKeyFileId(),
                shouldContinue = continueUpload,
            )
            preferences.markBackupSucceeded(backupResult, keyResult)
            return state()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            runCatching { preferences.markBackupFailed(error.message ?: "Google Drive backup failed") }
                .onFailure(error::addSuppressed)
            throw error
        } finally {
            keyRecord?.fill(0)
            material.keyBytes.fill(0)
            material.salt.fill(0)
            temporary?.let { runCatching { Files.deleteIfExists(it.toPath()) } }
        }
    }

    suspend fun deleteAllCloudData(accessToken: String): Int = withContext(Dispatchers.IO) {
        BACKUP_MUTEX.withLock {
            currentCoroutineContext().ensureActive()
            api.deleteAllAppData(accessToken)
        }
    }

    private fun cleanupAbandonedTemporaryBackups(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && TEMPORARY_BACKUP_NAME.matches(it.name) }
            .forEach { file -> runCatching { Files.deleteIfExists(file.toPath()) } }
    }

    private fun cleanupAbandonedRestoreFiles(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && RESTORE_FILE_NAME.matches(it.name) }
            .forEach { file ->
                if (file.extension == "zip") runCatching { securelyDeleteFile(file) }
                else runCatching { Files.deleteIfExists(file.toPath()) }
            }
    }

    private fun securelyDeleteFile(file: File) {
        if (!file.exists() || !file.isFile) return
        RandomAccessFile(file, "rws").use { output ->
            val zeros = ByteArray(64 * 1024)
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
        check(file.delete() || !file.exists()) { "Could not clean the restore workspace" }
    }

    private companion object {
        val BACKUP_MUTEX = Mutex()
        val TEMPORARY_BACKUP_NAME = Regex("kotj-[A-Za-z0-9_-]+\\.kbak")
        val RESTORE_FILE_NAME = Regex("kotj-restore-[A-Za-z0-9_-]+\\.(kbak|zip)")
    }
}
