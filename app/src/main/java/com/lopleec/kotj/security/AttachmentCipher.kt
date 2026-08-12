package com.lopleec.kotj.security

import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AttachmentCipher {
    private val magic = "KOTJIMG1".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 8 + PASSWORD_SALT_BYTES + GCM_IV_BYTES

    fun encrypt(plainBytes: ByteArray, password: CharArray, aad: String): ByteArray {
        require(password.isNotEmpty()) { "密码不能为空" }
        require(aad.isNotEmpty()) { "附件缺少绑定信息" }
        val salt = randomBytes(PASSWORD_SALT_BYTES)
        val iv = randomBytes(GCM_IV_BYTES)
        var keyBytes: ByteArray? = null
        return try {
            val derivedKey = derivePasswordKey(password, salt)
            keyBytes = derivedKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(plainBytes)
            ByteBuffer.allocate(HEADER_BYTES + encrypted.size)
                .put(magic)
                .put(salt)
                .put(iv)
                .put(encrypted)
                .array()
        } finally {
            keyBytes?.fill(0)
            password.fill('\u0000')
        }
    }

    fun decrypt(payload: ByteArray, password: CharArray, aad: String): ByteArray {
        var keyBytes: ByteArray? = null
        return try {
            require(payload.size >= HEADER_BYTES + GCM_TAG_BYTES) { "附件加密数据格式错误" }
            val buffer = ByteBuffer.wrap(payload)
            val payloadMagic = ByteArray(magic.size).also(buffer::get)
            require(payloadMagic.contentEquals(magic)) { "不支持的附件加密格式" }
            val salt = ByteArray(PASSWORD_SALT_BYTES).also(buffer::get)
            val iv = ByteArray(GCM_IV_BYTES).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val derivedKey = derivePasswordKey(password, salt)
            keyBytes = derivedKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            cipher.doFinal(encrypted)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("密码错误或附件已损坏")
        } finally {
            keyBytes?.fill(0)
            password.fill('\u0000')
        }
    }
}
