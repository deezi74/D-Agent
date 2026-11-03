package dev.dagent.core.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM envelope for one secret value: base64("v1" marker byte, 12-byte IV, ciphertext + tag).
 * The entry name is bound in as associated data, so a value copied under another name won't decrypt.
 * The key comes from the caller (the Android Keystore in the app, a plain key in tests).
 */
object SecretCodec {
    private const val VERSION: Byte = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun encrypt(key: SecretKey, name: String, value: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        // No IV passed: the provider makes a fresh random one (the Keystore insists on that).
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(name.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        require(iv != null && iv.size == IV_LEN) { "Unexpected GCM IV length" }
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + IV_LEN + ct.size)
        out[0] = VERSION
        System.arraycopy(iv, 0, out, 1, IV_LEN)
        System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.size)
        return Base64.getEncoder().encodeToString(out)
    }

    /** Throws if the blob is malformed, was made with another key, or was stored under another name. */
    fun decrypt(key: SecretKey, name: String, blob: String): String {
        val raw = Base64.getDecoder().decode(blob)
        require(raw.size > 1 + IV_LEN && raw[0] == VERSION) { "Not a secret blob" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 1, IV_LEN))
        c.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(c.doFinal(raw, 1 + IV_LEN, raw.size - 1 - IV_LEN), Charsets.UTF_8)
    }
}
