package dev.dagent.phone

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** [aiCaller] names the tool that can phone someone and talk for the person (manus or ai_call), when one is set up. */
class PhoneTool(
    private val phone: PhoneActions,
    private val aiCaller: () -> String? = { null },
    /** Ask the person each time whether a bot makes the call or they do. */
    private val askEachTime: () -> Boolean = { false },
) : Tool {
    override val name = "phone"
    override val description = "Use the phone: " + phone.actions.joinToString("; ") { "${it.name} (${it.usage})" } +
        ". Texts, calls and calendar changes ask the person first. Texts (sms_send) always go from the person's own number and SIM. call only dials so the person talks themselves; " +
        "if the AI should talk for them (tell someone something, ask a question, book something), use manus or ai_call instead."
    override val parameters = schema {
        title()
        string("action", "Which action.", required = true, enum = phone.actions.map { it.name })
        string("to", "Name or phone number (sms_send, sms_compose, call, email_compose).")
        string("text", "Text for notify, clipboard_set, sms, share, speak.")
        string("title", "Title for notify or calendar_add.")
        string("url", "Link for open_url.")
        string("name", "App name for open_app.")
        string("query", "Search words (contacts_search, calendar_events, list_apps, maps).")
        string("time", "Alarm time, e.g. 7:30 or 19:30.")
        string("label", "Alarm or timer label.")
        string("days", "Alarm repeat days, e.g. mon,tue,wed; or how many days of calendar to read.")
        integer("seconds", "Timer length.")
        string("start", "Event start, YYYY-MM-DD HH:MM.")
        string("end", "Event end, YYYY-MM-DD HH:MM.")
        string("location", "Event location.")
        string("notes", "Event notes.")
        string("subject", "Email subject.")
        string("body", "Email body.")
        string("mode", "maps: search or navigate.")
        boolean("on", "flashlight on or off.")
        integer("level", "Volume 0 to 100.")
        boolean("self", "call: true only when the person will talk on the call themselves.")
        string("sim", "SIM slot for call or sms_send: 1 or 2. Leave out to use the default.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val action = args.requireString("action")
        val ai = aiCaller()
        val useBot = when {
            action != "call" || ai == null || args.bool("self") == true -> false
            askEachTime() -> ctx.confirm(
                "Should your AI make this call and talk for you?",
                "Yes: " + (if (ai == "manus") "your Manus agent calls from its own number" else "Bland AI calls from its line") + " and does the talking.\n" +
                    "No: your phone dials from your own number and you talk.")
            else -> true
        }
        if (useBot) {
            return ToolResult.ok(
                (if (askEachTime()) "Not dialed: the person chose to have the AI make this call. " else "Not dialed. ") +
                "If the person wants the AI to do the talking (\"call X and tell her...\", \"ask them...\"), use the $ai tool " +
                    "with action call, to = the number or contact name, and task = what to say or find out; it calls from " +
                    (if (ai == "manus") "their Manus agent's own number" else "the AI caller's line") + " and talks for them. " +
                    "Only if they want to talk themselves, call phone again with self = true.")
        }
        val keepTitle = action == "notify" || action == "calendar_add"
        val map = args.raw.filterKeys { it != "action" && it != "self" && (it != "title" || keepTitle) }
            .mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
        // "title" doubles as the step title; for notify and calendar_add it is the real title.
        val fixed = if (action == "notify" && map["text"] == null) map + ("text" to (args.string("body") ?: "")) else map
        if (action == "calendar_events") {
            val days = args.int("days") ?: args.string("days")?.toIntOrNull()
            return ToolResult.ok(phone.run(action, fixed + ("days" to (days ?: 7).toString())))
        }
        return ToolResult.ok(phone.run(action, fixed))
    }
}
