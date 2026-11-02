package dev.dagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.dagent.core.agent.Persona
import dev.dagent.core.llm.ApiKind
import dev.dagent.core.llm.ProviderPreset
import dev.dagent.core.llm.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A provider as the person configured it: preset defaults plus their key, URL and model. */
data class ProviderConfig(
    val preset: ProviderPreset,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    val id get() = preset.id
    val kind: ApiKind get() = preset.kind
    val ready: Boolean get() = (!preset.keyRequired || apiKey.isNotBlank()) && baseUrl.isNotBlank() && model.isNotBlank()
}

data class AppPrefs(
    val providerId: String = "freellmapi",
    val agentMode: Boolean = true,
    /** Pick Chat or Agent per message (the default). */
    val autoMode: Boolean = true,
    val think: Boolean = false,
    val persona: Persona = Persona(),
    val avatarPath: String = "",
    val accent: Long = 0xFFFF6B35,
    val readAloud: Boolean = false,
    val confirmRisky: Boolean = true,
    val searxUrl: String = "",
    val visionOverride: Int = 0, // 0 auto, 1 yes, 2 no
    val localModelFile: String = "",
    val localContext: Int = 8192,
    val localThreads: Int = 0,
    val onboarded: Boolean = false,
    val defaultSim: Int = 0, // 0 phone default, 1 or 2
    val voiceProvider: String = "phone",
    val voiceName: String = "coral",
)

/** Plain preferences in one file; API keys in an encrypted one. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dagent", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context, "dagent_keys",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences("dagent_keys_fallback", Context.MODE_PRIVATE) }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppPrefs> = _state.asStateFlow()
    val current: AppPrefs get() = _state.value

    private val _providersVersion = MutableStateFlow(0)
    /** Bumps whenever a provider's key, URL or model changes. */
    val providersVersion: StateFlow<Int> = _providersVersion.asStateFlow()

    private fun load() = AppPrefs(
        providerId = prefs.getString("provider", "freellmapi")!!,
        agentMode = prefs.getBoolean("agentMode", true),
        autoMode = prefs.getBoolean("autoMode", true),
        think = prefs.getBoolean("think", false),
        persona = Persona(
            name = prefs.getString("p.name", "D")!!,
            style = prefs.getString("p.style", Persona.STYLE_WITTY)!!,
            customStyle = prefs.getString("p.custom", "")!!,
            userName = prefs.getString("p.user", "")!!,
            aboutUser = prefs.getString("p.about", "")!!,
        ),
        avatarPath = prefs.getString("avatar", "")!!,
        accent = prefs.getLong("accent", 0xFFFF6B35),
        readAloud = prefs.getBoolean("readAloud", false),
        confirmRisky = prefs.getBoolean("confirmRisky", true),
        searxUrl = prefs.getString("searx", "")!!,
        visionOverride = prefs.getInt("vision", 0),
        localModelFile = prefs.getString("local.file", "")!!,
        localContext = prefs.getInt("local.ctx", 8192),
        localThreads = prefs.getInt("local.threads", 0),
        onboarded = prefs.getBoolean("onboarded", false),
        defaultSim = prefs.getInt("sim", 0),
        voiceProvider = prefs.getString("voice.provider", "phone")!!,
        voiceName = prefs.getString("voice.name", "coral")!!,
    )

    fun update(block: (AppPrefs) -> AppPrefs) {
        val n = block(_state.value)
        prefs.edit()
            .putString("provider", n.providerId)
            .putBoolean("agentMode", n.agentMode)
            .putBoolean("autoMode", n.autoMode)
            .putBoolean("think", n.think)
            .putString("p.name", n.persona.name)
            .putString("p.style", n.persona.style)
            .putString("p.custom", n.persona.customStyle)
            .putString("p.user", n.persona.userName)
            .putString("p.about", n.persona.aboutUser)
            .putString("avatar", n.avatarPath)
            .putLong("accent", n.accent)
            .putBoolean("readAloud", n.readAloud)
            .putBoolean("confirmRisky", n.confirmRisky)
            .putString("searx", n.searxUrl)
            .putInt("vision", n.visionOverride)
            .putString("local.file", n.localModelFile)
            .putInt("local.ctx", n.localContext)
            .putInt("local.threads", n.localThreads)
            .putBoolean("onboarded", n.onboarded)
            .putInt("sim", n.defaultSim)
            .putString("voice.provider", n.voiceProvider)
            .putString("voice.name", n.voiceName)
            .apply()
        _state.value = n
    }

    fun provider(id: String = current.providerId): ProviderConfig {
        val preset = Providers.preset(id) ?: Providers.presets.first()
        return ProviderConfig(
            preset,
            baseUrl = prefs.getString("prov.$id.url", null) ?: preset.baseUrl,
            model = prefs.getString("prov.$id.model", null) ?: preset.defaultModel,
            apiKey = secrets.getString("key.$id", "") ?: "",
        )
    }

    fun allProviders(): List<ProviderConfig> = Providers.presets.map { provider(it.id) }

    fun saveProvider(id: String, baseUrl: String, model: String, apiKey: String) {
        prefs.edit().putString("prov.$id.url", baseUrl.trim()).putString("prov.$id.model", model.trim()).apply()
        secrets.edit().putString("key.$id", apiKey.trim()).apply()
        _providersVersion.value++
    }

    fun setModel(id: String, model: String) {
        prefs.edit().putString("prov.$id.model", model.trim()).apply()
        _providersVersion.value++
    }

    fun secret(name: String): String = secrets.getString("s.$name", "") ?: ""
    fun setSecret(name: String, value: String) {
        secrets.edit().putString("s.$name", value.trim()).apply()
        _providersVersion.value++
    }

    companion object {
        const val POLLINATIONS_KEY = "pollinations"
        const val ELEVENLABS_KEY = "elevenlabs"

        val ACCENTS = listOf(
            "Ember" to 0xFFFF6B35,
            "Gold" to 0xFFFFC447,
            "Ocean" to 0xFF4C9BFF,
            "Mint" to 0xFF3DD9A4,
            "Violet" to 0xFFA58BFF,
            "Rose" to 0xFFFF6F91,
        )
    }
}
