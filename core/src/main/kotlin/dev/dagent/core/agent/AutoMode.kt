package dev.dagent.core.agent

import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Role

/**
 * Picks Chat or Agent for each message so the person never has to. Chat is the fast path (fewer
 * tools, shorter prompt); Agent gets files, the Linux computer, the browser, phone actions and
 * scheduling. Anything unclear starts in Chat, which can still move itself up with [MoreToolsTool].
 */
object AutoMode {
    /** Tools only Agent mode has; using one recently keeps follow-ups in Agent. */
    val AGENT_ONLY = setOf("shell", "shell_job", "file_read", "file_write", "file_edit", "file_list", "browser", "phone", "schedule", "skill", "plan", "forget")

    private val DO = Regex(
        "\\b(" +
            // phone actions
            "call|phone|dial|text|sms|message (him|her|them|my)|alarm|timer|remind(er)?|calendar|contact|flashlight|volume|open (the )?app|" +
            // web and shopping
            "browse|go to|log ?in|sign ?in|fill (out|in)|order|buy|purchase|checkout|add to cart|book|reserve|reservation|track (my|the) (order|package)|price|" +
            // files and computers
            "file|files|folder|pdf|docx?|spreadsheet|csv|zip|upload|download|save (it|this|that)|workspace|" +
            "install|run|execute|command|terminal|termux|linux|shell|script|code|program|compile|debug|fix (the|my|this)|deploy|server|" +
            "pc|computer|laptop|github|repo|apk|app\\b|build|develop|" +
            // multi-step work
            "research|compare|investigate|analy[sz]e|summari[sz]e (this|these|the) (file|doc|pdf|page|site)|plan (my|a|the)|organi[sz]e|" +
            "schedule|every (day|morning|night|week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|automat|" +
            "scrape|crawl|convert|rename|resize|edit (my|the|this) (file|document|photo|video)" +
        ")\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Small talk and quick questions that never need Agent tools, even if a keyword shows up. */
    private val CHAT = Regex(
        "^(hi|hey|hello|yo|sup|thanks|thank you|thx|ok(ay)?|cool|nice|lol|lmao|good (morning|night|evening)|how are you|what'?s up|who are you)\\b[ !.?]*$",
        RegexOption.IGNORE_CASE,
    )

    data class Choice(val agent: Boolean, val reason: String)

    fun decide(text: String, files: Int, history: List<Msg>): Choice {
        val t = text.trim()
        if (files > 0) return Choice(true, "files attached")
        if (t.isEmpty() || CHAT.matches(t)) return Choice(false, "small talk")
        if (DO.containsMatchIn(t)) return Choice(true, "task")
        // A follow-up to work in progress ("now make it blue") stays in Agent.
        val recent = history.takeLast(8)
        if (recent.any { m -> m.role == Role.ASSISTANT && m.toolCalls.any { it.name in AGENT_ONLY || it.name == MoreToolsTool.NAME } }) {
            return Choice(true, "continuing a task")
        }
        // Long, detailed instructions are usually a job, not a question.
        if (t.length > 280) return Choice(true, "long request")
        return Choice(false, "question")
    }
}

/**
 * Offered in Chat mode only: lets the model ask for the Agent tools when it finds it needs them.
 * The runner sees the call and continues the same turn with every tool.
 */
class MoreToolsTool(private val onUse: () -> Unit) : Tool {
    override val name = NAME
    override val description =
        "Call this first if the request needs tools you don't have right now: reading or writing files, running code or commands " +
            "in the Linux computer, using a web browser (forms, logins, orders), phone actions (calls, texts, alarms, calendar), " +
            "scheduling, or long multi-step work. You'll get those tools and continue."
    override val parameters = schema { string("why", "One short reason.") }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        onUse()
        return ToolResult.ok("Agent tools are now available. Continue the person's request with them.")
    }

    companion object { const val NAME = "more_tools" }
}
