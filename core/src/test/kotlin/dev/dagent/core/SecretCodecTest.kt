package dev.dagent.core

import dev.dagent.core.security.SecretCodec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretCodecTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTrip() {
        val k = key()
        for (v in listOf("", "sk-abc123", "ünïcödé 🔑", "x".repeat(10_000))) {
            assertEquals(v, SecretCodec.decrypt(k, "key.openai", SecretCodec.encrypt(k, "key.openai", v)))
        }
    }

    @Test fun freshIvEachTime() {
        val k = key()
        assertNotEquals(SecretCodec.encrypt(k, "n", "same"), SecretCodec.encrypt(k, "n", "same"))
    }

    @Test fun wrongKeyNameOrTamperFails() {
        val k = key()
        val blob = SecretCodec.encrypt(k, "key.a", "secret")
        assertTrue(runCatching { SecretCodec.decrypt(key(), "key.a", blob) }.isFailure)
        assertTrue(runCatching { SecretCodec.decrypt(k, "key.b", blob) }.isFailure)
        val bytes = java.util.Base64.getDecoder().decode(blob)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertTrue(runCatching { SecretCodec.decrypt(k, "key.a", java.util.Base64.getEncoder().encodeToString(bytes)) }.isFailure)
        assertTrue(runCatching { SecretCodec.decrypt(k, "key.a", "not base64!") }.isFailure)
        assertTrue(runCatching { SecretCodec.decrypt(k, "key.a", "") }.isFailure)
    }
}
