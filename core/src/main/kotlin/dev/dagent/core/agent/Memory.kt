package dev.dagent.core.agent

import java.io.File
import java.time.LocalDate

/**
 * Long-term memory: one Markdown file of short facts. The newest part rides along in every
 * system prompt; the whole file is searchable with recall.
 */
class MemoryStore(dir: File) {
    val file = File(dir, "memory.md")

    init {
        dir.mkdirs()
        if (!file.exists()) file.writeText("# What I know about you\n\n")
    }

    @Synchronized fun all(): String = file.readText()

    @Synchronized fun facts(): List<String> = all().lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }

    @Synchronized fun add(fact: String): String {
        val clean = fact.trim().replace('\n', ' ')
        if (clean.isEmpty()) return "Nothing to remember."
        val existing = facts()
        if (existing.any { it.substringBeforeLast(" (").equals(clean, ignoreCase = true) }) return "Already remembered."
        file.appendText("- $clean (${LocalDate.now()})\n")
        return "Remembered: $clean"
    }

    @Synchronized fun forget(match: String): Int {
        val lines = all().lines()
        val keep = lines.filterNot { it.startsWith("- ") && it.contains(match, ignoreCase = true) }
        file.writeText(keep.joinToString("\n").trimEnd() + "\n")
        return lines.size - keep.size
    }

    @Synchronized fun replaceAll(text: String) = file.writeText(text)

    fun search(query: String): List<String> {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return facts()
        return facts().map { f -> f to words.count { f.lowercase().contains(it) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    private var cached: Triple<Long, Long, Int>? = null
    private var cachedText = ""

    /**
     * The part of memory included in the prompt: newest facts first, within [budget] characters.
     * Cached until memory.md changes (size or modified time), so building a prompt doesn't re-read it.
     */
    @Synchronized fun forPrompt(budget: Int = 5_000): String {
        val key = Triple(file.lastModified(), file.length(), budget)
        if (key == cached) return cachedText
        return buildPrompt(budget).also { cachedText = it; cached = key }
    }

    private fun buildPrompt(budget: Int): String {
        val out = mutableListOf<String>()
        var used = 0
        for (f in facts().asReversed()) {
            if (used + f.length > budget) break
            out += f
            used += f.length + 3
        }
        return out.asReversed().joinToString("\n") { "- $it" }
    }
}

class RememberTool(private val memory: MemoryStore) : Tool {
    override val name = "remember"
    override val description = "Save a lasting fact about the person or their world (preferences, names, plans, accounts they use, how they like things done). " +
        "Use it when they tell you something worth knowing next time, or say \"remember\". One short fact per call. Never store passwords or keys."
    override val parameters = schema {
        title()
        string("fact", "The fact, written so it makes sense on its own (e.g. \"Dominic's daughter is named Ava\").", required = true)
    }
    override suspend fun run(args: Args, ctx: ToolContext) = ToolResult.ok(memory.add(args.requireString("fact")))
}

class ForgetTool(private val memory: MemoryStore) : Tool {
    override val name = "forget"
    override val description = "Delete remembered facts that contain the given text, when the person asks you to forget something or a fact is wrong."
    override val parameters = schema {
        title()
        string("match", "Text that the facts to delete contain.", required = true)
    }
    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val n = memory.forget(args.requireString("match"))
        return ToolResult.ok(if (n == 0) "No remembered fact matched." else "Forgot $n fact${if (n > 1) "s" else ""}.")
    }
}

class RecallTool(private val memory: MemoryStore) : Tool {
    override val name = "recall"
    /** Only reads; safe to run alongside other read-only lookups. */
    override val readOnly = true
    override val description = "Search everything you have remembered about the person, including older facts that are not in your prompt."
    override val parameters = schema {
        title()
        string("query", "Words to look for.", required = true)
    }
    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val hits = memory.search(args.requireString("query"))
        return ToolResult.ok(if (hits.isEmpty()) "Nothing remembered about that." else hits.take(40).joinToString("\n") { "- $it" })
    }
}

/**
 * Skills are folders with a SKILL.md: a short `name:` and `description:` header and then
 * instructions. Their names and descriptions are in the prompt; the agent reads the full text
 * when a task matches, and can write new skills after it figures something out.
 */
class SkillLibrary(val dir: File) {
    data class Skill(val name: String, val description: String, val folder: File)

    init { dir.mkdirs() }

    private var cachedKey: List<String>? = null
    private var cachedList: List<Skill> = emptyList()

    /**
     * Every skill's name and description. Cached until a SKILL.md is added, removed or changed
     * (checked by path, size and modified time), so prompts don't re-read every file each message.
     */
    @Synchronized fun list(): List<Skill> {
        val files = dir.listFiles()?.map { File(it, "SKILL.md") }?.filter { it.isFile }.orEmpty()
        val key = files.map { "${it.path}|${it.length()}|${it.lastModified()}" }.sorted()
        if (key == cachedKey) return cachedList
        return scan().also { cachedList = it; cachedKey = key }
    }

    private fun scan(): List<Skill> = dir.listFiles()?.filter { File(it, "SKILL.md").isFile }?.mapNotNull { folder ->
        val text = File(folder, "SKILL.md").readText()
        val head = frontmatter(text)
        Skill(head["name"] ?: folder.name, head["description"] ?: text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("---") }.orEmpty().take(160), folder)
    }?.sortedBy { it.name } ?: emptyList()

    fun read(name: String): String? {
        val skill = list().firstOrNull { it.name.equals(name, true) || it.folder.name.equals(name, true) } ?: return null
        val extra = skill.folder.walkTopDown().filter { it.isFile && it.name != "SKILL.md" }.map { it.relativeTo(skill.folder).path }.toList()
        return File(skill.folder, "SKILL.md").readText() +
            (if (extra.isNotEmpty()) "\n\nFiles in this skill (in Linux at /skills/${skill.folder.name}/): ${extra.joinToString()}" else "")
    }

    fun save(name: String, description: String, body: String): File {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "skill" }
        val folder = File(dir, slug).apply { mkdirs() }
        File(folder, "SKILL.md").writeText("---\nname: $slug\ndescription: ${description.replace('\n', ' ')}\n---\n\n${body.trim()}\n")
        return folder
    }

    fun delete(name: String): Boolean = list().firstOrNull { it.name == name || it.folder.name == name }?.folder?.deleteRecursively() == true

    companion object {
        fun frontmatter(text: String): Map<String, String> {
            if (!text.startsWith("---")) return emptyMap()
            val end = text.indexOf("\n---", 3).takeIf { it > 0 } ?: return emptyMap()
            return text.substring(3, end).lines().mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"')
            }.toMap()
        }
    }
}

class SkillTool(private val skills: SkillLibrary) : Tool {
    override val name = "skill"
    override val description = "Read a skill's full instructions before doing a task it covers (action=read), or save what you just learned as a new reusable skill (action=save) " +
        "after you worked out a multi-step procedure the person may want again."
    override val parameters = schema {
        title()
        string("action", "read or save.", required = true, enum = listOf("read", "save"))
        string("name", "Skill name.", required = true)
        string("description", "For save: one sentence on when to use it.")
        string("instructions", "For save: the steps, commands and gotchas, in Markdown.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val name = args.requireString("name")
        return when (args.requireString("action")) {
            "save" -> {
                val folder = skills.save(name, args.requireString("description"), args.requireString("instructions"))
                ToolResult.ok("Saved skill \"${folder.name}\". It will be listed in your prompt from now on.")
            }
            else -> skills.read(name)?.let { ToolResult.ok(it) }
                ?: ToolResult.error("No skill named \"$name\". Known: ${skills.list().joinToString { it.name }}")
        }
    }
}

/** A live checklist for multi-step tasks, shown above the chat while the agent works. */
class PlanBoard {
    data class Step(val text: String, val status: String)
    @Volatile var steps: List<Step> = emptyList()
        private set
    var onChange: ((List<Step>) -> Unit)? = null

    fun set(newSteps: List<Step>) {
        steps = newSteps
        onChange?.invoke(newSteps)
    }
}

class PlanTool(private val board: PlanBoard) : Tool {
    override val name = "plan"
    override val description = "Show the person a checklist for a task with several steps, and update it as you go. " +
        "Write each step as \"[x] done\", \"[>] in progress\" or \"[ ] to do\". Send the whole list every time. Skip it for quick questions."
    override val parameters = schema {
        title()
        stringArray("steps", "Every step, in order, each starting with [x], [>] or [ ].", required = true)
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val steps = args.stringList("steps").map { raw ->
            val t = raw.trim()
            val status = when {
                t.startsWith("[x]", true) || t.startsWith("✓") -> "done"
                t.startsWith("[>]") || t.startsWith("[~]") -> "active"
                else -> "todo"
            }
            PlanBoard.Step(t.replace(Regex("^(\\[[ xX>~]]|✓)\\s*"), ""), status)
        }
        board.set(steps)
        val done = steps.count { it.status == "done" }
        return ToolResult.ok("Plan updated: $done of ${steps.size} done.")
    }
}
