package com.lopleec.kotj.backup

import com.lopleec.kotj.security.PASSWORD_SALT_BYTES
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Portable backup-key record stored beside the encrypted snapshot in Drive appDataFolder.
 *
 * The record is deliberately not protected by a device-only key: access to the selected Google
 * Account and this app's drive.appdata scope is the recovery credential. The snapshot itself stays
 * AES-GCM encrypted, while the record checksum catches truncation/corruption before restoration.
 */
internal object DriveCloudKeyRecord {
    fun fingerprint(backupFileId: String): String {
        require(FILE_ID.matches(backupFileId)) { "Invalid backup file ID" }
        val idBytes = backupFileId.toByteArray(Charsets.US_ASCII)
        val digest = try {
            MessageDigest.getInstance(DIGEST).digest(idBytes)
        } finally {
            idBytes.fill(0)
        }
        return try {
            buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        } finally {
            digest.fill(0)
        }
    }

    fun encode(material: BackupKeyMaterial, backupFileId: String): ByteArray {
        require(material.keyBytes.size == KEY_BYTES) { "Invalid cloud backup key" }
        require(material.salt.size == PASSWORD_SALT_BYTES) { "Invalid cloud backup salt" }
        require(FILE_ID.matches(backupFileId)) { "Invalid backup file ID" }
        val idBytes = backupFileId.toByteArray(Charsets.US_ASCII)
        val payloadLength = HEADER_BYTES + idBytes.size + KEY_BYTES + PASSWORD_SALT_BYTES
        val record = ByteArray(payloadLength + DIGEST_BYTES)
        var digestInput: ByteArray? = null
        var digest: ByteArray? = null
        try {
            ByteBuffer.wrap(record).apply {
                put(MAGIC)
                putInt(FORMAT_VERSION)
                putShort(idBytes.size.toShort())
                put(idBytes)
                put(material.keyBytes)
                put(material.salt)
            }
            digestInput = record.copyOf(payloadLength)
            digest = MessageDigest.getInstance(DIGEST).digest(digestInput)
            System.arraycopy(digest, 0, record, payloadLength, digest.size)
            return record
        } finally {
            idBytes.fill(0)
            digestInput?.fill(0)
            digest?.fill(0)
        }
    }

    fun decode(record: ByteArray, expectedBackupFileId: String): BackupKeyMaterial {
        require(FILE_ID.matches(expectedBackupFileId)) { "Invalid backup file ID" }
        require(record.size in MIN_RECORD_BYTES..MAX_RECORD_BYTES) { "Invalid cloud backup key record" }
        val buffer = ByteBuffer.wrap(record)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Unsupported cloud backup key record" }
        require(buffer.int == FORMAT_VERSION) { "Unsupported cloud backup key version" }
        val idLength = buffer.short.toInt() and 0xffff
        val expectedLength = HEADER_BYTES + idLength + KEY_BYTES + PASSWORD_SALT_BYTES + DIGEST_BYTES
        require(idLength in MIN_FILE_ID_BYTES..MAX_FILE_ID_BYTES && record.size == expectedLength) {
            "Invalid cloud backup key record"
        }

        val idBytes = ByteArray(idLength)
        var digestInput: ByteArray? = null
        var expectedDigest: ByteArray? = null
        var actualDigest: ByteArray? = null
        var keyBytes: ByteArray? = null
        var salt: ByteArray? = null
        var succeeded = false
        return try {
            buffer.get(idBytes)
            val backupFileId = idBytes.toString(Charsets.US_ASCII)
            require(backupFileId == expectedBackupFileId && FILE_ID.matches(backupFileId)) {
                "Cloud backup key belongs to a different backup"
            }
            val decodedKey = ByteArray(KEY_BYTES).also(buffer::get)
            keyBytes = decodedKey
            val decodedSalt = ByteArray(PASSWORD_SALT_BYTES).also(buffer::get)
            salt = decodedSalt
            val decodedDigest = ByteArray(DIGEST_BYTES).also(buffer::get)
            actualDigest = decodedDigest
            digestInput = record.copyOf(record.size - DIGEST_BYTES)
            expectedDigest = MessageDigest.getInstance(DIGEST).digest(digestInput)
            require(MessageDigest.isEqual(expectedDigest, decodedDigest)) {
                "Cloud backup key record is damaged"
            }
            succeeded = true
            BackupKeyMaterial(decodedKey, decodedSalt)
        } finally {
            magic.fill(0)
            idBytes.fill(0)
            digestInput?.fill(0)
            expectedDigest?.fill(0)
            actualDigest?.fill(0)
            if (!succeeded) {
                keyBytes?.fill(0)
                salt?.fill(0)
            }
        }
    }

    private val MAGIC = "KOTJCKY1".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 1
    private const val KEY_BYTES = 32
    private const val DIGEST_BYTES = 32
    private const val DIGEST = "SHA-256"
    private const val HEX = "0123456789abcdef"
    private const val MIN_FILE_ID_BYTES = 10
    private const val MAX_FILE_ID_BYTES = 200
    private val FILE_ID = Regex("[A-Za-z0-9_-]{$MIN_FILE_ID_BYTES,$MAX_FILE_ID_BYTES}")
    private val HEADER_BYTES = MAGIC.size + Int.SIZE_BYTES + Short.SIZE_BYTES
    private val MIN_RECORD_BYTES = HEADER_BYTES + MIN_FILE_ID_BYTES + KEY_BYTES + PASSWORD_SALT_BYTES + DIGEST_BYTES
    const val MAX_RECORD_BYTES = 512
}
