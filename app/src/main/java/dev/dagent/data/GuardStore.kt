package dev.dagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.dagent.core.agent.ActivityEntry
import dev.dagent.core.agent.ActivitySink
import dev.dagent.core.agent.GuardRule
import dev.dagent.core.agent.Risk
import dev.dagent.core.agent.SecretSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/** The person's guard choices: a rule per tool, plus the two switches. */
class GuardSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dagent_guard", Context.MODE_PRIVATE)
    private val _rules = MutableStateFlow(loadRules())
    val rules: StateFlow<Map<String, GuardRule>> = _rules.asStateFlow()

    var reviewer: Boolean
        get() = prefs.getBoolean("reviewer", true)
        set(v) { prefs.edit().putBoolean("reviewer", v).apply() }

    var mailFilter: Boolean
        get() = prefs.getBoolean("mailFilter", true)
        set(v) { prefs.edit().putBoolean("mailFilter", v).apply() }

    fun set(tool: String, rule: GuardRule) {
        prefs.edit().apply { if (rule == GuardRule.AUTO) remove("rule.$tool") else putString("rule.$tool", rule.name) }.apply()
        _rules.value = loadRules()
    }

    private fun loadRules(): Map<String, GuardRule> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith("rule.")) return@mapNotNull null
        val rule = runCatching { GuardRule.valueOf(v as String) }.getOrNull() ?: return@mapNotNull null
        k.removePrefix("rule.") to rule
    }.toMap()
}

/** Every tool step, newest last, as JSON lines in the app's private storage. */
class ActivityLog(dir: File) : ActivitySink {
    private val file = File(dir, "activity.jsonl")
    private val _entries = MutableStateFlow(read())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    @Synchronized
    override fun record(entry: ActivityEntry) {
        val list = (_entries.value + entry).takeLast(MAX)
        _entries.value = list
        runCatching {
            if (list.size == MAX && file.length() > 600_000) file.writeText(list.joinToString("") { encode(it) + "\n" })
            else file.appendText(encode(entry) + "\n")
        }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        file.delete()
    }

    private fun read(): List<ActivityEntry> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().takeLast(MAX).mapNotNull { line -> runCatching { decode(Json.parseToJsonElement(line).jsonObject) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun encode(e: ActivityEntry) = buildJsonObject {
        put("t", e.time); put("s", e.sessionId); put("tool", e.tool); put("title", e.title); put("d", e.detail)
        put("risk", e.risk.name); put("v", e.verdict); put("ok", e.ok); put("r", e.result); put("ms", e.millis)
    }.toString()

    private fun decode(o: JsonObject) = ActivityEntry(
        time = o["t"]!!.jsonPrimitive.long, sessionId = o["s"]!!.jsonPrimitive.content, tool = o["tool"]!!.jsonPrimitive.content,
        title = o["title"]!!.jsonPrimitive.content, detail = o["d"]!!.jsonPrimitive.content,
        risk = runCatching { Risk.valueOf(o["risk"]!!.jsonPrimitive.content) }.getOrDefault(Risk.LOCAL),
        verdict = o["v"]!!.jsonPrimitive.content, ok = o["ok"]!!.jsonPrimitive.boolean,
        result = o["r"]!!.jsonPrimitive.content, millis = o["ms"]!!.jsonPrimitive.long,
    )

    companion object { const val MAX = 1500 }
}

/**
 * Passwords, card numbers and other secrets, encrypted with a key kept in the Android Keystore.
 * The agent only learns the names; the guard types the real value into a web page at the last
 * step and wipes it from anything that comes back.
 */
class SecretVault(context: Context) : SecretSource {
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context, "dagent_vault",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()
    private val _names = MutableStateFlow(load())
    val namesFlow: StateFlow<Set<String>> = _names.asStateFlow()

    /** False when this phone's keystore failed; then nothing is saved rather than saved in the clear. */
    val available: Boolean get() = prefs != null

    override fun names(): Set<String> = _names.value
    override fun value(name: String): String? = prefs?.getString(clean(name), null)

    fun put(name: String, value: String): Boolean {
        val p = prefs ?: return false
        val key = clean(name)
        if (key.isEmpty() || value.isEmpty()) return false
        p.edit().putString(key, value).apply()
        _names.value = load() + key
        return true
    }

    fun remove(name: String) {
        prefs?.edit()?.remove(name)?.apply()
        _names.value = load() - name
    }

    private fun load(): Set<String> = prefs?.all?.keys?.toSortedSet() ?: emptySet()

    companion object {
        /** Names are short lowercase words so they read well in {{vault:name}}. */
        fun clean(name: String) = name.trim().lowercase().replace(Regex("[^a-z0-9_.-]+"), "_").trim('_').take(40)
    }
}
