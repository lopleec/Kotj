package com.lopleec.kotj.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lopleec.kotj.security.GCM_IV_BYTES
import com.lopleec.kotj.security.PASSWORD_SALT_BYTES
import com.lopleec.kotj.security.randomBytes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BackupKeyMaterial(
    val keyBytes: ByteArray,
    val salt: ByteArray,
)

/**
 * Caches the account-managed backup key for WorkManager. The local copy is wrapped by a
 * non-exportable Android Keystore key; its portable copy lives only in the selected Google
 * Account's private Drive appDataFolder.
 */
class DriveBackupKeyStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = runCatching {
        load().also {
            it.keyBytes.fill(0)
            it.salt.fill(0)
        }
    }.isSuccess

    fun generateRandomKey() {
        val keyBytes = randomBytes(KEY_BYTES)
        val salt = randomBytes(PASSWORD_SALT_BYTES)
        try {
            storeKeyMaterial(keyBytes, salt)
        } finally {
            keyBytes.fill(0)
            salt.fill(0)
        }
    }

    fun storeKeyMaterial(keyBytes: ByteArray, salt: ByteArray) {
        require(keyBytes.size == KEY_BYTES) { "Invalid backup encryption key" }
        require(salt.size == PASSWORD_SALT_BYTES) { "Invalid backup salt" }
        var wrappedKey: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            wrappedKey = cipher.doFinal(keyBytes)
            val record = listOf(RECORD_VERSION, salt.b64(), cipher.iv.b64(), wrappedKey.b64()).joinToString(":")
            check(preferences.edit().putString(KEY_WRAPPED_BACKUP_KEY, record).commit()) {
                "Could not save backup encryption key"
            }
        } finally {
            wrappedKey?.fill(0)
        }
    }

    fun clear() {
        check(preferences.edit().remove(KEY_WRAPPED_BACKUP_KEY).commit()) {
            "Could not clear backup encryption key"
        }
        // The encrypted record is already gone, so an unlikely failure deleting the orphaned,
        // non-exportable Keystore key must not leave the Google account marked as connected.
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun load(): BackupKeyMaterial {
        val parts = requireNotNull(preferences.getString(KEY_WRAPPED_BACKUP_KEY, null)) {
            "Backup encryption key is unavailable"
        }.split(':')
        require(parts.size == 4 && parts[0] == RECORD_VERSION) { "Invalid backup encryption key" }
        var salt: ByteArray? = null
        var iv: ByteArray? = null
        var wrapped: ByteArray? = null
        var key: ByteArray? = null
        var succeeded = false
        return try {
            val decodedSalt = parts[1].fromB64().also { require(it.size == PASSWORD_SALT_BYTES) }
            salt = decodedSalt
            val decodedIv = parts[2].fromB64().also { require(it.size == GCM_IV_BYTES) }
            iv = decodedIv
            val decodedWrapped = parts[3].fromB64()
            wrapped = decodedWrapped
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, decodedIv))
            val decodedKey = cipher.doFinal(decodedWrapped)
            key = decodedKey
            require(decodedKey.size == KEY_BYTES) { "Invalid backup encryption key" }
            succeeded = true
            BackupKeyMaterial(decodedKey, decodedSalt)
        } finally {
            if (!succeeded) {
                key?.fill(0)
                salt?.fill(0)
            }
            iv?.fill(0)
            wrapped?.fill(0)
        }
    }

    private fun getOrCreateWrappingKey(): SecretKey = runCatching(::wrappingKey).getOrElse {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return requireNotNull(keyStore.getKey(KEY_ALIAS, null) as? SecretKey) {
            "Backup wrapping key is unavailable"
        }
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    companion object {
        private const val PREFERENCES_NAME = "google_drive_backup_encryption"
        private const val KEY_WRAPPED_BACKUP_KEY = "wrapped_backup_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "kotj_drive_backup_wrap_v1"
        private const val RECORD_VERSION = "KOTJKEY1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
    }
}
