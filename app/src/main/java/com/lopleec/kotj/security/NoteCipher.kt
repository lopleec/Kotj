package com.lopleec.kotj.security

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object NoteCipher {
    private const val PREFIX = "KOTJ1"

    fun encrypt(plainText: String, password: CharArray): String {
        require(password.isNotEmpty()) { "密码不能为空" }
        val salt = randomBytes(PASSWORD_SALT_BYTES)
        val iv = randomBytes(GCM_IV_BYTES)
        var keyBytes: ByteArray? = null
        return try {
            val derivedKey = derivePasswordKey(password, salt)
            keyBytes = derivedKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, iv))
            val plainBytes = plainText.toByteArray(Charsets.UTF_8)
            val encrypted = try {
                cipher.doFinal(plainBytes)
            } finally {
                plainBytes.fill(0)
            }
            listOf(PREFIX, salt.b64(), iv.b64(), encrypted.b64()).joinToString(":")
        } finally {
            keyBytes?.fill(0)
            password.fill('\u0000')
        }
    }

    @Throws(IllegalArgumentException::class)
    fun decrypt(payload: String, password: CharArray): String {
        var keyBytes: ByteArray? = null
        return try {
            val parts = payload.split(':')
            require(parts.size == 4 && parts[0] == PREFIX) { "不支持的加密数据" }
            val salt = parts[1].fromB64()
            val iv = parts[2].fromB64()
            val encrypted = parts[3].fromB64()
            require(salt.size == PASSWORD_SALT_BYTES && iv.size == GCM_IV_BYTES && encrypted.size >= GCM_TAG_BYTES) { "加密数据格式错误" }
            val derivedKey = derivePasswordKey(password, salt)
            keyBytes = derivedKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(encrypted)
            try {
                plainBytes.toString(Charsets.UTF_8)
            } finally {
                plainBytes.fill(0)
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("密码错误或数据已损坏")
        } finally {
            keyBytes?.fill(0)
            password.fill('\u0000')
        }
    }

    private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)
}
