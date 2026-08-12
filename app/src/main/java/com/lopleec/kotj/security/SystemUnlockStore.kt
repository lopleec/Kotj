package com.lopleec.kotj.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only AES-GCM wrapped note passwords. The wrapping key lives in Android Keystore and every
 * encrypt/decrypt operation requires a fresh strong biometric or device-credential authentication.
 */
class SystemUnlockStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("system_unlock_credentials", Context.MODE_PRIVATE)

    fun hasPassword(noteId: String): Boolean = preferences.contains(noteKey(noteId))

    fun isSystemOnly(noteId: String): Boolean = preferences.getBoolean(modeKey(noteId), false)

    fun generateRandomPassword(): String {
        val random = ByteArray(RANDOM_PASSWORD_BYTES).also(SecureRandom()::nextBytes)
        return try {
            Base64.encodeToString(random, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        } finally {
            random.fill(0)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun newEncryptionCipher(): Cipher =
        newCipher().apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

    @RequiresApi(Build.VERSION_CODES.R)
    fun newDecryptionCipher(noteId: String): Cipher? {
        val record = preferences.getString(noteKey(noteId), null) ?: return null
        val parts = record.split(':')
        if (parts.size != 2) {
            remove(noteId)
            return null
        }
        val iv = runCatching {
            Base64.decode(parts[0], Base64.NO_WRAP).also {
                require(it.size == GCM_IV_BYTES)
            }
        }.getOrElse {
            remove(noteId)
            return null
        }
        return runCatching {
            newCipher().apply { init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv)) }
        }.getOrNull()
    }

    fun savePassword(
        noteId: String,
        password: CharArray,
        authenticatedCipher: Cipher,
        systemOnly: Boolean = false,
    ) {
        val bytes = password.concatToString().toByteArray(Charsets.UTF_8)
        try {
            val encrypted = authenticatedCipher.doFinal(bytes)
            val record = listOf(authenticatedCipher.iv, encrypted)
                .joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
            preferences.edit(commit = true) {
                putString(noteKey(noteId), record)
                putBoolean(modeKey(noteId), systemOnly)
            }
        } finally {
            bytes.fill(0)
            password.fill('\u0000')
        }
    }

    fun recoverPassword(noteId: String, authenticatedCipher: Cipher): String {
        val record = requireNotNull(preferences.getString(noteKey(noteId), null)) { "没有可用的系统解锁信息" }
        val encrypted = Base64.decode(record.substringAfter(':'), Base64.NO_WRAP)
        val plain = authenticatedCipher.doFinal(encrypted)
        return try {
            plain.toString(Charsets.UTF_8)
        } finally {
            plain.fill(0)
            encrypted.fill(0)
        }
    }

    fun remove(noteId: String) {
        preferences.edit {
            remove(noteKey(noteId))
            remove(modeKey(noteId))
        }
    }

    fun cleanupOrphans(validNoteIds: Set<String>) {
        val staleKeys = preferences.all.keys.filter { key ->
            val noteId = when {
                key.startsWith(NOTE_PREFIX) -> key.removePrefix(NOTE_PREFIX)
                key.startsWith(MODE_PREFIX) -> key.removePrefix(MODE_PREFIX)
                else -> return@filter false
            }
            noteId !in validNoteIds
        }
        if (staleKeys.isNotEmpty()) {
            preferences.edit { staleKeys.forEach { key -> remove(key) } }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getOrCreateKey(): SecretKey = runCatching { getKey() }.getOrElse {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    .build(),
            )
        }.generateKey()
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return requireNotNull(keyStore.getKey(KEY_ALIAS, null) as? SecretKey) { "系统解锁密钥不可用" }
    }

    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun noteKey(noteId: String): String = NOTE_PREFIX + noteId

    private fun modeKey(noteId: String): String = MODE_PREFIX + noteId

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kotj_system_unlock_v1"
        const val NOTE_PREFIX = "note_"
        const val MODE_PREFIX = "system_only_"
        const val RANDOM_PASSWORD_BYTES = 32
    }
}
