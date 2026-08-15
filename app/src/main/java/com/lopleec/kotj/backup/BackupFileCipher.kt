package com.lopleec.kotj.backup

import com.lopleec.kotj.security.GCM_IV_BYTES
import com.lopleec.kotj.security.PASSWORD_SALT_BYTES
import com.lopleec.kotj.security.PBKDF2_ITERATIONS
import com.lopleec.kotj.security.randomBytes
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object BackupFileCipher {
    data class Header(
        val salt: ByteArray,
        val iterations: Int,
        internal val iv: ByteArray,
        internal val encoded: ByteArray,
    )

    fun encrypt(
        output: OutputStream,
        keyBytes: ByteArray,
        salt: ByteArray,
        writePlaintext: (OutputStream) -> Unit,
    ) {
        require(keyBytes.size == KEY_BYTES) { "Backup key must be 256 bits" }
        require(salt.size == PASSWORD_SALT_BYTES) { "Invalid backup salt" }
        val iv = randomBytes(GCM_IV_BYTES)
        val header = encodeHeader(salt, iv)
        try {
            output.write(header)
            output.flush()

            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            CipherOutputStream(output, cipher).use(writePlaintext)
        } finally {
            iv.fill(0)
            header.fill(0)
        }
    }

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        keyBytes: ByteArray,
    ) {
        require(keyBytes.size == KEY_BYTES) { "Backup key must be 256 bits" }
        val header = readHeader(input)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, header.iv))
        cipher.updateAAD(header.encoded)
        val encryptedBuffer = ByteArray(STREAM_BUFFER_BYTES)
        try {
            while (true) {
                val count = input.read(encryptedBuffer)
                if (count < 0) break
                cipher.update(encryptedBuffer, 0, count)?.let { plaintext ->
                    output.write(plaintext)
                    plaintext.fill(0)
                }
            }
            cipher.doFinal()?.let { plaintext ->
                output.write(plaintext)
                plaintext.fill(0)
            }
            output.flush()
        } finally {
            encryptedBuffer.fill(0)
            header.salt.fill(0)
            header.iv.fill(0)
            header.encoded.fill(0)
        }
    }

    fun readHeader(input: InputStream): Header {
        val encoded = DataInputStream(input).run {
            ByteArray(HEADER_BYTES).also(::readFully)
        }
        val buffer = ByteBuffer.wrap(encoded)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Not a Kotj encrypted backup" }
        require(buffer.int == FORMAT_VERSION) { "Unsupported backup version" }
        val iterations = buffer.int
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid backup key derivation settings" }
        val salt = ByteArray(PASSWORD_SALT_BYTES).also(buffer::get)
        val iv = ByteArray(GCM_IV_BYTES).also(buffer::get)
        return Header(salt, iterations, iv, encoded)
    }

    private fun encodeHeader(salt: ByteArray, iv: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC)
            .putInt(FORMAT_VERSION)
            .putInt(PBKDF2_ITERATIONS)
            .put(salt)
            .put(iv)
            .array()

    private val MAGIC = "KOTJBKP1".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 1
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 5_000_000
    private val HEADER_BYTES = MAGIC.size + Int.SIZE_BYTES * 2 + PASSWORD_SALT_BYTES + GCM_IV_BYTES
}
