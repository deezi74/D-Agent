package dev.dagent.core.llm

import okhttp3.OkHttpClient

enum class ApiKind { OPENAI, ANTHROPIC }

/** A place D-Agent can get answers from. Presets are below; people can add their own. */
data class ProviderPreset(
    val id: String,
    val name: String,
    val kind: ApiKind,
    val baseUrl: String,
    val defaultModel: String,
    /** Where to get a key; empty when none is needed. */
    val keyUrl: String = "",
    val keyRequired: Boolean = true,
    val reasoning: ReasoningStyle = ReasoningStyle.NONE,
    val headers: Map<String, String> = emptyMap(),
    val blurb: String = "",
)

object Providers {
    const val LOCAL_ID = "on_phone"
    const val LOCAL_PORT = 8765

    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            "freellmapi", "FreeLLMAPI (Termux)", ApiKind.OPENAI, "http://localhost:3001/v1", "auto",
            keyRequired = false,
            blurb = "Your free model router running in Termux. Start it with: cd ~/freellmapi && termux-wake-lock && npm run dev",
        ),
        ProviderPreset(
            LOCAL_ID, "On this phone", ApiKind.OPENAI, "http://127.0.0.1:$LOCAL_PORT/v1", "local",
            keyRequired = false,
            blurb = "A downloaded GGUF model running offline on the phone. Pick one in Settings > On-phone models.",
        ),
        ProviderPreset(
            "openrouter", "OpenRouter", ApiKind.OPENAI, "https://openrouter.ai/api/v1", "openrouter/auto",
            keyUrl = "https://openrouter.ai/keys", reasoning = ReasoningStyle.OPENROUTER,
            headers = mapOf("HTTP-Referer" to "https://github.com/deezi74/D-Agent", "X-Title" to "D-Agent"),
            blurb = "Hundreds of models behind one key, including free ones (model names ending in :free).",
        ),
        ProviderPreset(
            "anthropic", "Anthropic (Claude)", ApiKind.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5-5",
            keyUrl = "https://console.anthropic.com/settings/keys",
            blurb = "Claude models. Strong at long agent tasks and tool use.",
        ),
        ProviderPreset(
            "openai", "OpenAI", ApiKind.OPENAI, "https://api.openai.com/v1", "gpt-5-mini",
            keyUrl = "https://platform.openai.com/api-keys", reasoning = ReasoningStyle.EFFORT,
        ),
        ProviderPreset(
            "gemini", "Google Gemini", ApiKind.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash",
            keyUrl = "https://aistudio.google.com/apikey", reasoning = ReasoningStyle.EFFORT,
            blurb = "Generous free tier. Sees images.",
        ),
        ProviderPreset(
            "xai", "xAI (Grok)", ApiKind.OPENAI, "https://api.x.ai/v1", "grok-4",
            keyUrl = "https://console.x.ai",
        ),
        ProviderPreset(
            "groq", "Groq", ApiKind.OPENAI, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
            keyUrl = "https://console.groq.com/keys", blurb = "Very fast open models with a free tier.",
        ),
        ProviderPreset(
            "mistral", "Mistral", ApiKind.OPENAI, "https://api.mistral.ai/v1", "mistral-medium-latest",
            keyUrl = "https://console.mistral.ai/api-keys",
        ),
        ProviderPreset(
            "together", "Together AI", ApiKind.OPENAI, "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            keyUrl = "https://api.together.ai/settings/api-keys",
        ),
        ProviderPreset(
            "ollama", "Ollama", ApiKind.OPENAI, "http://localhost:11434/v1", "llama3.2",
            keyRequired = false, blurb = "An Ollama server on this phone (Termux) or your network.",
        ),
        ProviderPreset(
            "custom", "Custom (OpenAI-compatible)", ApiKind.OPENAI, "http://localhost:8080/v1", "",
            keyRequired = false, blurb = "Any server that speaks the OpenAI chat completions API.",
        ),
    )

    fun preset(id: String): ProviderPreset? = presets.firstOrNull { it.id == id }

    fun client(
        http: OkHttpClient,
        kind: ApiKind,
        baseUrl: String,
        apiKey: String,
        reasoning: ReasoningStyle = ReasoningStyle.NONE,
        headers: Map<String, String> = emptyMap(),
    ): LlmClient = when (kind) {
        ApiKind.OPENAI -> OpenAiClient(http, baseUrl, apiKey, headers, reasoning)
        ApiKind.ANTHROPIC -> AnthropicClient(http, baseUrl, apiKey)
    }
}
