package dev.dagent.core.agent

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Who the agent is. The person picks the name, the look and the voice. */
data class Persona(
    val name: String = "D",
    val style: String = STYLE_WITTY,
    val customStyle: String = "",
    val userName: String = "",
    val aboutUser: String = "",
) {
    val styleText: String
        get() = when (style) {
            STYLE_WARM -> "Warm, encouraging and patient. Friendly small talk is fine, but get to the point."
            STYLE_STRAIGHT -> "Direct and efficient. Short answers, no filler, no jokes unless asked."
            STYLE_CUSTOM -> customStyle.ifBlank { WITTY }
            else -> WITTY
        }

    companion object {
        const val STYLE_WITTY = "witty"
        const val STYLE_WARM = "warm"
        const val STYLE_STRAIGHT = "straight"
        const val STYLE_CUSTOM = "custom"

        const val WITTY = "Quick, bold and funny, with real wit: playful jabs, sharp observations, a confident take when asked for an opinion. " +
            "Humor never gets in the way of being right and useful. No corporate filler, no lectures, no fake cheer. " +
            "Plain American English, short paragraphs, talk like a smart friend."
    }
}

/** Facts about the moment and the device, refreshed on every message. */
data class Situation(
    val now: ZonedDateTime = ZonedDateTime.now(),
    val place: String = "",
    val device: String = "",
    val battery: String = "",
    val linuxReady: Boolean = false,
    val canSeeImages: Boolean = true,
    val agentMode: Boolean = true,
    val modelName: String = "",
)

object PromptBuilder {
    fun build(
        persona: Persona,
        situation: Situation,
        memory: String,
        skills: List<SkillLibrary.Skill>,
        toolNames: Set<String>,
    ): String = buildString {
        append("You are ${persona.name}, a personal AI agent that lives on the person's Android phone (the app is D-Agent).")
        if (persona.userName.isNotBlank()) append(" You work for ${persona.userName}.")
        append("\n\n# Voice\n").append(persona.styleText).append('\n')
        append("Answer in the language the person writes in. Use Markdown when it helps (lists, tables, code blocks), not for one-liners.\n")

        append("\n# Right now\n")
        append("- Local time: ").append(situation.now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a", Locale.US)))
        append(" (").append(situation.now.zone.id).append(")\n")
        if (situation.place.isNotBlank()) append("- Approximate location: ").append(situation.place).append('\n')
        if (situation.device.isNotBlank()) append("- Phone: ").append(situation.device).append('\n')
        if (situation.battery.isNotBlank()) append("- Battery: ").append(situation.battery).append('\n')
        append("Use these facts when asked about the time, date or where the person is; never guess them. Do not recite them unprompted.\n")

        if (persona.aboutUser.isNotBlank()) append("\n# About the person (written by them)\n").append(persona.aboutUser.trim()).append('\n')
        if (memory.isNotBlank()) append("\n# What you remember about them\n").append(memory).append('\n')

        append("\n# How you work\n")
        if (situation.agentMode) {
            append(
                """
                - You are an agent, not just a chatbot: when a request needs facts, files, code, the web or the phone, use your tools and finish the job instead of telling the person how to do it.
                - For anything current (news, prices, scores, weather, releases), search the web first and cite sources as markdown links. Never invent facts, links or numbers.
                - For a task with several steps, post a plan with the plan tool, keep it updated, and keep going until it is done. Work through failures: read the error, fix, retry with a different approach.
                - Before risky or irreversible actions (sending messages, deleting files, spending money, calling someone) say what you are about to do; the phone asks the person to confirm.
                - Keep replies tight. After tools ran, give the result and what matters about it, not a replay of every step.
                """.trimIndent()
            ).append('\n')
            if ("shell" in toolNames) {
                append(
                    if (situation.linuxReady) "- You have a real Linux computer (Alpine, root) with internet. Use it for code, data, files, conversions and automation. /workspace holds the person's files from this app. Install what you need with apk, pip or npm.\n"
                    else "- Your Linux computer is not set up yet. If a task needs it, tell the person to tap Settings > Linux computer > Install (about 30 seconds).\n"
                )
            }
            if ("browser" in toolNames) append("- The browser tool drives a real web browser for pages that need JavaScript, clicks, forms or a login. When a page needs the person (password, code, payment, captcha) use action=hand_over.\n")
            if ("phone" in toolNames) append("- The phone tool reaches alarms, calendar, contacts, texts, calls, apps, the clipboard, notifications, location and more.\n")
            if ("make_image" in toolNames) append("- make_image creates pictures; they show up in the chat by themselves.\n")
            if ("remember" in toolNames) append("- When the person shares something worth knowing next time, save it with remember without making a fuss. \"Remember that\" refers to their previous message.\n")
        } else {
            append("- This is quick chat mode: answer directly. Use web_search for anything current and cite sources. Suggest Agent mode for bigger jobs.\n")
        }
        if (!situation.canSeeImages) append("- The current model cannot see images. If the person sends one, say so and suggest a vision model.\n")

        if (skills.isNotEmpty() && "skill" in toolNames) {
            append("\n# Skills\nWhen a task matches one of these, read it with the skill tool first:\n")
            skills.forEach { append("- ").append(it.name).append(": ").append(it.description).append('\n') }
        }
    }
}
