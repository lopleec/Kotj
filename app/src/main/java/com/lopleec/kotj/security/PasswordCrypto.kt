package com.lopleec.kotj.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal const val PASSWORD_SALT_BYTES = 16
internal const val GCM_IV_BYTES = 12
internal const val GCM_TAG_BYTES = 16
internal const val PBKDF2_ITERATIONS = 210_000
private val secureRandom = SecureRandom()

internal fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

internal fun derivePasswordKey(
    password: CharArray,
    salt: ByteArray,
): ByteArray {
    val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}
