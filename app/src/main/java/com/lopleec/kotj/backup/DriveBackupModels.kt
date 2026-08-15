package com.lopleec.kotj.backup

enum class DriveAuthorizationPurpose { BACKUP, RESTORE, DELETE_CLOUD_DATA }

data class DriveBackupUiState(
    val accountDisplayName: String? = null,
    val accountEmail: String? = null,
    val encryptionReady: Boolean = false,
    val backupInProgress: Boolean = false,
    val restoreInProgress: Boolean = false,
    val authorizationRequired: Boolean = false,
    val remoteBackupCheckCompleted: Boolean = false,
    val remoteBackupAvailable: Boolean = false,
    val remoteKeyAvailable: Boolean = false,
    val restoreRequired: Boolean = false,
    val remoteBackupModifiedAt: Long? = null,
    val remoteBackupBytes: Long? = null,
    val lastBackupAt: Long? = null,
    val lastBackupBytes: Long? = null,
    val lastError: String? = null,
)

data class GoogleUserInfo(
    val displayName: String?,
    val email: String,
)

data class DriveUploadResult(
    val fileId: String,
    val size: Long,
    val modifiedAt: Long,
)

data class DriveBackupFile(
    val fileId: String,
    val size: Long,
    val modifiedAt: Long,
)

data class DriveBackupKeyFile(
    val fileId: String,
    val backupFingerprint: String,
    val size: Long,
    val modifiedAt: Long,
)

data class DriveBackupSet(
    val backup: DriveBackupFile?,
    val keyFile: DriveBackupKeyFile?,
)

internal fun requiresCloudRestore(
    remoteBackup: DriveBackupFile?,
    locallyOwnedBackupId: String?,
    hasLocalKey: Boolean,
): Boolean = remoteBackup != null && (!hasLocalKey || locallyOwnedBackupId != remoteBackup.fileId)

/** A paired key proves this is the account-only format; a timestamp change then means another device wrote it. */
internal fun requiresMergeBeforeUpload(
    remote: DriveBackupSet,
    localBackupId: String?,
    lastSeenRemoteModifiedAt: Long?,
): Boolean {
    val backup = remote.backup ?: return false
    if (backup.fileId != localBackupId) return true
    if (remote.keyFile == null) return false // The owning installation may still need to publish its legacy key.
    return lastSeenRemoteModifiedAt == null || backup.modifiedAt != lastSeenRemoteModifiedAt
}

internal fun requiresCloudMerge(
    remote: DriveBackupSet,
    localBackupId: String?,
    hasLocalKey: Boolean,
    lastSeenRemoteModifiedAt: Long?,
    mergeWasAlreadyRequired: Boolean,
): Boolean {
    val backup = remote.backup ?: return false
    val legacyMigrationAllowed = hasLocalKey && backup.fileId == localBackupId && remote.keyFile == null
    return requiresCloudRestore(backup, localBackupId, hasLocalKey) ||
        (mergeWasAlreadyRequired && !legacyMigrationAllowed) ||
        requiresMergeBeforeUpload(remote, localBackupId, lastSeenRemoteModifiedAt)
}

data class DriveRestoreResult(
    val noteCount: Int,
    val categoryCount: Int,
    val attachmentCount: Int,
    val backupCreatedAt: Long,
    val importedNoteCount: Int,
    val updatedNoteCount: Int,
    val retainedLocalNoteCount: Int,
)
