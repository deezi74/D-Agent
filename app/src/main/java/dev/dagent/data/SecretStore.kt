package dev.dagent.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.dagent.core.security.SecretCodec
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * API keys and other secrets, each value encrypted with AES-256-GCM under a key that lives in the
 * Android Keystore and never leaves it. Every value has its own random IV, stored with the
 * ciphertext in the "dagent_secrets" SharedPreferences file. Entry names are not secret
 * ("key.openai", "s.github") and stay readable.
 *
 * [open] fails (rather than falling back to plain text) when the Keystore can't make or use the key.
 */
class SecretStore private constructor(private val prefs: SharedPreferences, private val key: SecretKey) {

    /** The value, or null when missing. Throws if the stored value can't be decrypted. */
    fun get(name: String): String? = prefs.getString(name, null)?.let { SecretCodec.decrypt(key, name, it) }

    fun put(name: String, value: String) {
        prefs.edit().putString(name, SecretCodec.encrypt(key, name, value)).apply()
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    fun contains(name: String) = prefs.contains(name)

    companion object {
        private const val FILE = "dagent_secrets"
        private const val ALIAS = "dagent_secret_store_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MIGRATED = "__migrated_v1"

        // Where secrets lived before this store.
        private const val OLD_ENCRYPTED = "dagent_keys"
        private const val OLD_PLAIN = "dagent_keys_fallback"

        /** Opens the store (making the Keystore key the first time) and moves old secrets into it. */
        fun open(context: Context): Result<SecretStore> = runCatching {
            val store = SecretStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), keystoreKey())
            // Proves the key really works on this device before anything is trusted to it.
            check(SecretCodec.decrypt(store.key, "probe", SecretCodec.encrypt(store.key, "probe", "ok")) == "ok")
            store
        }.onSuccess { migrate(context, it) }

        private fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return gen.generateKey()
        }

        /**
         * One time: copies the old EncryptedSharedPreferences file and then the plain-text fallback
         * (only names not already copied) into the new store, then deletes each old file. If the old
         * encrypted file can't be opened it is kept and the move is tried again on the next launch;
         * the plain-text file is always removed once copied.
         */
        private fun migrate(context: Context, store: SecretStore) {
            if (store.prefs.getBoolean(MIGRATED, false)) return
            var done = true
            if (exists(context, OLD_ENCRYPTED)) {
                runCatching {
                    val old = EncryptedSharedPreferences.create(
                        context, OLD_ENCRYPTED,
                        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                    copy(old, store, overwrite = true)
                    old.edit().clear().commit()
                    context.deleteSharedPreferences(OLD_ENCRYPTED)
                }.onFailure { done = false }
            }
            if (exists(context, OLD_PLAIN)) {
                val plain = context.getSharedPreferences(OLD_PLAIN, Context.MODE_PRIVATE)
                copy(plain, store, overwrite = false)
                plain.edit().clear().commit()
                context.deleteSharedPreferences(OLD_PLAIN)
            }
            if (done) store.prefs.edit().putBoolean(MIGRATED, true).apply()
        }

        /** Copies every string entry and writes it to disk before returning (the old file is deleted next). */
        private fun copy(from: SharedPreferences, to: SecretStore, overwrite: Boolean) {
            val edit = to.prefs.edit()
            for ((name, value) in from.all) {
                if (value !is String || name == MIGRATED) continue
                if (!overwrite && to.contains(name)) continue
                edit.putString(name, SecretCodec.encrypt(to.key, name, value))
            }
            check(edit.commit()) { "Couldn't save the moved secrets" }
        }

        private fun exists(context: Context, name: String) =
            java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists()
    }
}
