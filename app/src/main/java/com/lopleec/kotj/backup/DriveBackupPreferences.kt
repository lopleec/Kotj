package com.lopleec.kotj.backup

import android.content.Context
import android.content.SharedPreferences

class DriveBackupPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(encryptionReady: Boolean): DriveBackupUiState = DriveBackupUiState(
        accountDisplayName = preferences.getString(KEY_ACCOUNT_NAME, null),
        accountEmail = preferences.getString(KEY_ACCOUNT_EMAIL, null),
        encryptionReady = encryptionReady,
        authorizationRequired = preferences.getBoolean(KEY_AUTHORIZATION_REQUIRED, false),
        remoteBackupCheckCompleted = preferences.getBoolean(KEY_REMOTE_BACKUP_CHECKED, false),
        remoteBackupAvailable = preferences.getString(KEY_REMOTE_BACKUP_FILE_ID, null) != null,
        remoteKeyAvailable = preferences.getString(KEY_REMOTE_KEY_FILE_ID, null) != null,
        restoreRequired = preferences.getBoolean(KEY_RESTORE_REQUIRED, false),
        remoteBackupModifiedAt = preferences.getLong(KEY_REMOTE_BACKUP_MODIFIED_AT, 0L).takeIf { it > 0L },
        remoteBackupBytes = preferences.getLong(KEY_REMOTE_BACKUP_BYTES, -1L).takeIf { it >= 0L },
        lastBackupAt = preferences.getLong(KEY_LAST_BACKUP_AT, 0L).takeIf { it > 0L },
        lastBackupBytes = preferences.getLong(KEY_LAST_BACKUP_BYTES, -1L).takeIf { it >= 0L },
        lastError = preferences.getString(KEY_LAST_ERROR, null),
    )

    fun saveAccount(user: GoogleUserInfo) {
        commitState {
            putAccount(user)
            putBoolean(KEY_AUTHORIZATION_REQUIRED, false)
            remove(KEY_LAST_ERROR)
        }
    }

    /**
     * Changes the Google Account without reusing Drive file ownership metadata from the previous
     * account. The portable encryption key is intentionally managed separately by
     * [DriveBackupKeyStore], so local notes can be backed up to an empty new account and an
     * existing new-account backup can replace it only through the explicit merge flow.
     */
    fun replaceAccount(user: GoogleUserInfo) {
        commitState {
            putAccount(user)
            removeDriveOwnership()
            putBoolean(KEY_AUTHORIZATION_REQUIRED, false)
            remove(KEY_LAST_ERROR)
        }
    }

    fun clearAccount() {
        commitState {
            remove(KEY_ACCOUNT_NAME)
            remove(KEY_ACCOUNT_EMAIL)
            removeDriveOwnership()
            remove(KEY_LAST_ERROR)
            putBoolean(KEY_AUTHORIZATION_REQUIRED, false)
        }
    }

    fun accountEmail(): String? = preferences.getString(KEY_ACCOUNT_EMAIL, null)

    fun driveFileId(): String? = preferences.getString(KEY_DRIVE_FILE_ID, null)

    fun driveKeyFileId(): String? = preferences.getString(KEY_DRIVE_KEY_FILE_ID, null)

    fun remoteBackupModifiedAt(): Long? =
        preferences.getLong(KEY_REMOTE_BACKUP_MODIFIED_AT, 0L).takeIf { it > 0L }

    fun restoreRequired(): Boolean = preferences.getBoolean(KEY_RESTORE_REQUIRED, false)

    fun savePendingBackup(backup: DriveUploadResult) {
        commitState {
            putString(KEY_DRIVE_FILE_ID, backup.fileId)
            putString(KEY_REMOTE_BACKUP_FILE_ID, backup.fileId)
            putLong(KEY_REMOTE_BACKUP_MODIFIED_AT, backup.modifiedAt)
            putLong(KEY_REMOTE_BACKUP_BYTES, backup.size)
            putBoolean(KEY_REMOTE_BACKUP_CHECKED, true)
            // Until the paired key upload succeeds, account switching must remain blocked.
            remove(KEY_REMOTE_KEY_FILE_ID)
        }
    }

    fun saveRemoteBackup(set: DriveBackupSet, restoreRequired: Boolean) {
        commitState {
            putBoolean(KEY_REMOTE_BACKUP_CHECKED, true)
            putBoolean(KEY_RESTORE_REQUIRED, restoreRequired)
            if (set.backup == null) {
                remove(KEY_REMOTE_BACKUP_FILE_ID)
                remove(KEY_REMOTE_BACKUP_MODIFIED_AT)
                remove(KEY_REMOTE_BACKUP_BYTES)
            } else {
                putString(KEY_REMOTE_BACKUP_FILE_ID, set.backup.fileId)
                putLong(KEY_REMOTE_BACKUP_MODIFIED_AT, set.backup.modifiedAt)
                putLong(KEY_REMOTE_BACKUP_BYTES, set.backup.size)
            }
            if (set.keyFile == null) remove(KEY_REMOTE_KEY_FILE_ID)
            else putString(KEY_REMOTE_KEY_FILE_ID, set.keyFile.fileId)
        }
    }

    fun markBackupSucceeded(backup: DriveUploadResult, keyFile: DriveUploadResult) {
        commitState {
            putString(KEY_DRIVE_FILE_ID, backup.fileId)
            putString(KEY_DRIVE_KEY_FILE_ID, keyFile.fileId)
            putLong(KEY_LAST_BACKUP_AT, backup.modifiedAt)
            putLong(KEY_LAST_BACKUP_BYTES, backup.size)
            putString(KEY_REMOTE_BACKUP_FILE_ID, backup.fileId)
            putString(KEY_REMOTE_KEY_FILE_ID, keyFile.fileId)
            putLong(KEY_REMOTE_BACKUP_MODIFIED_AT, backup.modifiedAt)
            putLong(KEY_REMOTE_BACKUP_BYTES, backup.size)
            putBoolean(KEY_REMOTE_BACKUP_CHECKED, true)
            putBoolean(KEY_RESTORE_REQUIRED, false)
            putBoolean(KEY_AUTHORIZATION_REQUIRED, false)
            remove(KEY_LAST_ERROR)
        }
    }

    fun markRestoreSucceeded(set: DriveBackupSet) {
        val backup = requireNotNull(set.backup)
        val keyFile = requireNotNull(set.keyFile)
        commitState {
            putString(KEY_DRIVE_FILE_ID, backup.fileId)
            putString(KEY_DRIVE_KEY_FILE_ID, keyFile.fileId)
            putString(KEY_REMOTE_BACKUP_FILE_ID, backup.fileId)
            putString(KEY_REMOTE_KEY_FILE_ID, keyFile.fileId)
            putLong(KEY_REMOTE_BACKUP_MODIFIED_AT, backup.modifiedAt)
            putLong(KEY_REMOTE_BACKUP_BYTES, backup.size)
            putBoolean(KEY_REMOTE_BACKUP_CHECKED, true)
            putBoolean(KEY_RESTORE_REQUIRED, false)
            putLong(KEY_LAST_BACKUP_AT, backup.modifiedAt)
            putLong(KEY_LAST_BACKUP_BYTES, backup.size)
            putBoolean(KEY_AUTHORIZATION_REQUIRED, false)
            remove(KEY_LAST_ERROR)
        }
    }

    fun markBackupFailed(message: String) {
        commitState { putString(KEY_LAST_ERROR, message.take(MAX_ERROR_LENGTH)) }
    }

    fun markAuthorizationRequired() {
        commitState {
            putBoolean(KEY_AUTHORIZATION_REQUIRED, true)
            putString(KEY_LAST_ERROR, "Google Drive authorization is required")
        }
    }

    /**
     * Recovery ownership and [KEY_RESTORE_REQUIRED] are safety-critical. Never continue a cloud
     * operation if Android could not durably persist their new values.
     */
    @Suppress("UseKtx")
    private inline fun commitState(update: SharedPreferences.Editor.() -> Unit) {
        val editor = preferences.edit().apply(update)
        check(editor.commit()) { "Could not save Google Drive backup state" }
    }

    private fun SharedPreferences.Editor.putAccount(user: GoogleUserInfo) {
        putString(KEY_ACCOUNT_NAME, user.displayName?.takeIf(String::isNotBlank))
        putString(KEY_ACCOUNT_EMAIL, user.email)
    }

    private fun SharedPreferences.Editor.removeDriveOwnership() {
        remove(KEY_DRIVE_FILE_ID)
        remove(KEY_DRIVE_KEY_FILE_ID)
        remove(KEY_REMOTE_BACKUP_FILE_ID)
        remove(KEY_REMOTE_KEY_FILE_ID)
        remove(KEY_REMOTE_BACKUP_MODIFIED_AT)
        remove(KEY_REMOTE_BACKUP_BYTES)
        remove(KEY_REMOTE_BACKUP_CHECKED)
        remove(KEY_RESTORE_REQUIRED)
        remove(KEY_LAST_BACKUP_AT)
        remove(KEY_LAST_BACKUP_BYTES)
    }

    companion object {
        private const val PREFERENCES_NAME = "google_drive_backup_state"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_DRIVE_FILE_ID = "drive_file_id"
        private const val KEY_DRIVE_KEY_FILE_ID = "drive_key_file_id"
        private const val KEY_REMOTE_BACKUP_FILE_ID = "remote_backup_file_id"
        private const val KEY_REMOTE_KEY_FILE_ID = "remote_key_file_id"
        private const val KEY_REMOTE_BACKUP_CHECKED = "remote_backup_checked"
        private const val KEY_RESTORE_REQUIRED = "restore_required"
        private const val KEY_REMOTE_BACKUP_MODIFIED_AT = "remote_backup_modified_at"
        private const val KEY_REMOTE_BACKUP_BYTES = "remote_backup_bytes"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_BACKUP_BYTES = "last_backup_bytes"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_AUTHORIZATION_REQUIRED = "authorization_required"
        private const val MAX_ERROR_LENGTH = 240
    }
}
