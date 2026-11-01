package dev.dagent.character

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title

/** Lets the person change the agent's face from the chat: "make yourself a robot fox". */
class CharacterTool(private val studio: CharacterStudio) : Tool {
    override val name = "character"
    override val description = "Change your own avatar (your face in the app). " +
        "create: draw 4 options of a character from a description in a style, shown to the person; then ask which one they like. " +
        "pick: adopt option 1-4 (you then get a pose for each mood). saved: list saved characters. use: switch to a saved one by number. " +
        "clear: go back to the photo or monogram. Styles: " + CharacterStudio.STYLES.joinToString(", ") { it.id }
    override val parameters = schema {
        title()
        string("action", "create, pick, saved, use or clear.", required = true, enum = listOf("create", "pick", "saved", "use", "clear"))
        string("description", "create: who the character is (species, look, outfit, colors, vibe), in English.")
        string("style", "create: art style id.", enum = CharacterStudio.STYLES.map { it.id })
        integer("option", "pick: 1-4. use: number from saved.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult = when (args.requireString("action")) {
        "create" -> {
            val description = args.string("description")?.takeIf { it.isNotBlank() } ?: return ToolResult.error("Say who the character is in description.")
            ctx.progress("Drawing 4 options...")
            studio.makeOptions(description, args.string("style") ?: "toy").join()
            val slots = studio.slots.value
            val files = slots.mapNotNull { (it as? CharacterStudio.Slot.Ready)?.file }
            if (files.isEmpty()) ToolResult.error("No option could be drawn: " + slots.filterIsInstance<CharacterStudio.Slot.Failed>().firstOrNull()?.message.orEmpty())
            else ToolResult.ok(
                "Drew options " + slots.mapIndexedNotNull { i, s -> if (s is CharacterStudio.Slot.Ready) "${i + 1}" else null }.joinToString(", ") +
                    " (in order, shown to the person). Ask which one they want, then call pick. They can also pick in Settings > look and personality.",
                files = files,
            )
        }
        "pick" -> {
            val n = args.int("option") ?: return ToolResult.error("Give option 1-4.")
            runCatching { studio.pick(n - 1) }.fold(
                { ToolResult.ok("Now using option $n as your face. Mood poses (working, done, waiting, oops) are being drawn in the background.") },
                { ToolResult.error(it.message ?: "Couldn't use that option.") },
            )
        }
        "saved" -> {
            val list = studio.saved()
            if (list.isEmpty()) ToolResult.ok("No saved characters yet.")
            else ToolResult.ok(list.mapIndexed { i, l -> "${i + 1}. ${l.description} (${l.style})" + if (studio.active.value?.id == l.id) " - in use" else "" }.joinToString("\n"))
        }
        "use" -> {
            val look = studio.saved().getOrNull((args.int("option") ?: 0) - 1) ?: return ToolResult.error("No saved character with that number.")
            studio.use(look)
            ToolResult.ok("Switched to: ${look.description}.")
        }
        "clear" -> { studio.clear(); ToolResult.ok("Back to the photo or monogram.") }
        else -> ToolResult.error("Unknown action.")
    }
}
