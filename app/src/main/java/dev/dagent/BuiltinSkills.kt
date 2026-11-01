package dev.dagent

import android.content.Context
import dev.dagent.core.agent.SkillLibrary
import java.io.File

/** Skills that ship with the app. Installed once; the person (or the agent) can edit them later. */
object BuiltinSkills {
    private const val VERSION = 1

    private val skills = listOf(
        Triple(
            "research-report",
            "Deep research on a topic with sources, written up as a report file.",
            """
            # Research report

            1. Post a plan: questions to answer, sources to check, the write-up.
            2. Run 3 to 6 web_search calls with different angles (overview, latest news, data, critics, official sources). Add the year for current topics.
            3. Open the 4 to 8 best results with web_fetch (browser only if a page needs JavaScript). Prefer primary sources.
            4. Note facts with their source URL as you go. When sources disagree, say so.
            5. Write /workspace/reports/<topic>.md: summary first (5 bullets), then sections, then a Sources list of markdown links.
            6. In chat: the key findings in a few lines and the file path. Offer a deeper dive on one part.
            """.trimIndent(),
        ),
        Triple(
            "build-website",
            "Make a website or web app (HTML, CSS, JavaScript) and preview it on the phone.",
            """
            # Build a website

            1. Ask at most one question if the goal is unclear; otherwise pick a sensible design and go.
            2. Write files under /workspace/sites/<name>/ with file_write: index.html, style.css, app.js. No build step; use CDN links for libraries.
            3. Mobile first: viewport meta, readable font sizes, a real color palette, no lorem ipsum.
            4. Preview: shell `cd /workspace/sites/<name> && python3 -m http.server 8090` with background=true, then browser open http://127.0.0.1:8090 and take a screenshot to check the look.
            5. Fix what looks off. Stop the server job when done.
            6. Tell the person the folder path and how to open it again.
            """.trimIndent(),
        ),
        Triple(
            "data-analysis",
            "Analyze a CSV, spreadsheet or numbers with Python and make charts.",
            """
            # Data analysis

            1. Make sure Python has what it needs: `apk add py3-pandas py3-matplotlib py3-numpy` (faster than pip on Alpine).
            2. Look at the data first: shape, columns, a few rows, missing values.
            3. Write the analysis as a script in /workspace/analysis/ and run it, so it can be re-run.
            4. Save charts as PNG in /workspace/analysis/ (plt.savefig, dpi=150, tight layout, clear titles and labels).
            5. Read the PNG back with file_read to check it, then report the findings in plain words with the numbers that matter.
            """.trimIndent(),
        ),
        Triple(
            "phone-automation",
            "Write scripts that control the phone (notifications, clipboard, texts, alarms) from Linux, optionally on a schedule.",
            """
            # Phone automation

            Inside Linux the `phone` command reaches the phone: `phone help` lists actions.
            Examples:
            - `phone notify title="Done" text="Backup finished"`
            - `phone clipboard_set text="hello"`
            - `phone battery`
            - `phone location`
            Texts, calls and calendar changes ask the person first.

            For something that should happen later or every day, use the schedule tool with a prompt that runs the script, e.g. "Run sh /workspace/scripts/check.sh and tell me the result".
            Keep scripts in /workspace/scripts/ and make them print a one-line summary.
            """.trimIndent(),
        ),
    )

    fun install(context: Context, library: SkillLibrary) {
        val marker = File(context.filesDir, ".skills-v$VERSION")
        if (marker.exists()) return
        skills.forEach { (name, description, body) ->
            if (!File(library.dir, "$name/SKILL.md").exists()) library.save(name, description, body)
        }
        marker.writeText("ok")
    }
}
