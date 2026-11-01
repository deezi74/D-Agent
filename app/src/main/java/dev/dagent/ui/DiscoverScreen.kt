package dev.dagent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.content.FeedPost
import dev.dagent.content.FeedWriter
import dev.dagent.content.Goal
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime

data class Idea(val emoji: String, val category: String, val title: String, val prompt: String)

val IDEAS = listOf(
    Idea("🗓️", "Plan", "Plan my week", "Help me plan this week. Ask me for my fixed commitments first, then propose a realistic schedule and save it as a file."),
    Idea("🍳", "Plan", "Dinner from my fridge", "I'll list what's in my fridge. Suggest three dinners I can make in under 30 minutes, with steps."),
    Idea("✈️", "Plan", "Weekend trip", "Plan a two-day weekend trip. Ask where I'm starting from and my budget, then build a day-by-day itinerary with places and drive times."),
    Idea("🧾", "Money", "Budget check-in", "Walk me through a quick monthly budget check-in: income, fixed costs, spending categories, and one change to make."),
    Idea("💸", "Money", "Side hustle ideas", "Give me five side hustle ideas that fit what you know about me, with startup cost, time per week and first three steps."),
    Idea("🛒", "Money", "Compare before I buy", "I'm about to buy something. Ask me what it is, then search for current prices, common complaints and better alternatives."),
    Idea("🧠", "Learn", "Teach me in 5 minutes", "Teach me something useful in five minutes. Pick the topic from what you know about me, then quiz me with 3 questions."),
    Idea("📚", "Learn", "Sum up a long article", "I'll paste a link. Read it and give me the five points that matter plus anything that sounds off."),
    Idea("💻", "Build", "Build me a web page", "Build me a one-page website. Ask what it's for, then make it in your Linux computer and show me a preview."),
    Idea("📈", "Build", "Analyze a spreadsheet", "I'll share a CSV or Excel file. Clean it, find the trends, chart them and tell me the three things that matter."),
    Idea("💪", "Health", "Ten-minute workout", "Give me a 10-minute no-equipment workout I can do right now. Adjust if I say something hurts."),
    Idea("😴", "Health", "Fix my sleep", "Ask me five questions about my sleep habits, then give me a two-week plan with one change at a time."),
    Idea("💬", "People", "Draft a tricky message", "Help me write a message I've been avoiding. Ask who it's for and what I need to say, then give me two versions."),
    Idea("🎁", "People", "Gift ideas", "Help me find a gift. Ask who it's for, what they like and my budget, then suggest five ideas with where to buy."),
    Idea("🎬", "Create", "Make a short video", "Make me a short AI video. Ask what it should show and the vibe, then write a cinematic prompt and make it."),
    Idea("🎨", "Create", "Name it", "I need a name for something (a project, a pet, a business). Ask about it and give me 15 options in different styles."),
    Idea("🔥", "Fun", "Roast me (nicely)", "Roast my habits based on what you know about me. Keep it funny, finish with one genuinely useful tip."),
    Idea("🎲", "Fun", "Decide for me", "I can't decide between some options. Ask me what they are, weigh them honestly, and pick one."),
)

private val GOAL_CATEGORIES = listOf("💼" to "Work", "💰" to "Money", "💪" to "Health", "📚" to "Learn", "❤️" to "People", "🌱" to "Personal")

/** Feed, Goals (with routines) and Ideas: the things to do besides chatting. */
@Composable
fun DiscoverScreen(g: Graph, back: () -> Unit, startChat: (String) -> Unit) {
    val p = LocalPalette.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.text) }
            listOf("Feed", "Goals", "Ideas").forEachIndexed { i, label ->
                val on = tab == i
                Text(
                    label, color = if (on) Color(0xFF1A0E08) else p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(50)).background(if (on) p.accent else p.surface)
                        .clickable { tab = i }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        when (tab) {
            0 -> FeedTab(g, startChat)
            1 -> GoalsTab(g, startChat)
            else -> IdeasTab(g, startChat)
        }
    }
}

@Composable
private fun FeedTab(g: Graph, startChat: (String) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val posts by g.content.feed.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var steering by remember { mutableStateOf(!g.content.steerSet) }
    var steer by remember { mutableStateOf(g.content.steer) }
    val name = g.settings.current.persona.name
    fun refresh(trending: Boolean) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            runCatching { g.feedWriter.write(trending = trending) }.onFailure { error = it.message ?: "Feed failed" }
            busy = false
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your feed", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Written by $name from what you care about", color = p.muted, fontSize = 12.sp)
                }
                TextButton({ refresh(true) }, enabled = !busy) { Text("Trending") }
                IconButton({ refresh(false) }, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = p.accent, strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "Write new posts", tint = p.text)
                }
            }
        }
        item {
            if (steering) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tell me what to write about", color = p.text, fontWeight = FontWeight.SemiBold)
                    Text("One sentence is enough. Likes and dislikes tune it over time.", color = p.muted, fontSize = 12.sp)
                    OutlinedTextField(steer, { steer = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ g.content.steer = steer; steering = false; if (posts.isEmpty()) refresh(false) }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Save") }
                        OutlinedButton({ steering = false }) { Text("Not now") }
                    }
                }
            } else {
                Column {
                    Text("“${g.content.steer}”", color = p.muted, fontSize = 12.sp)
                    TextButton({ steering = true }) { Text("Steer the feed") }
                }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent) }
        if (error.isNotBlank()) item { Text(error, color = p.bad, fontSize = 13.sp) }
        if (posts.isEmpty() && !busy) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nothing here yet", color = p.text, fontWeight = FontWeight.SemiBold)
                Text("Tap refresh and $name writes a few short posts. Add the \"Morning feed\" routine in Goals to get them every day.", color = p.muted, fontSize = 13.sp)
                Button({ refresh(false) }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Write my feed") }
            }
        }
        items(posts, key = { it.id }) { post ->
            PostCard(post,
                onLike = { g.content.react(post.id, 1) },
                onDislike = { g.content.react(post.id, -1) },
                onDiscuss = { startChat("Tell me more about \"${post.title}\": ${post.body}") },
                onOpen = post.url?.let { url -> { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } },
                onDelete = { g.content.deletePost(post.id) })
        }
        if (posts.isNotEmpty()) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton({ g.content.clearFeed() }) { Text("Clear feed", color = p.muted) } }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun PostCard(post: FeedPost, onLike: () -> Unit, onDislike: () -> Unit, onDiscuss: () -> Unit, onOpen: (() -> Unit)?, onDelete: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(post.emoji, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(post.title, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(ago(post.created), color = p.muted, fontSize = 11.sp)
            }
        }
        Text(post.body, color = p.text, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onLike) { Icon(Icons.Default.ThumbUp, "More like this", tint = if (post.reaction > 0) p.accent else p.muted, modifier = Modifier.size(18.dp)) }
            IconButton(onDislike) { Icon(Icons.Default.ThumbDown, "Less like this", tint = if (post.reaction < 0) p.accent else p.muted, modifier = Modifier.size(18.dp)) }
            IconButton(onDiscuss) { Icon(Icons.Default.ChatBubbleOutline, "Talk about it", tint = p.muted, modifier = Modifier.size(18.dp)) }
            if (onOpen != null) IconButton(onOpen) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open source", tint = p.muted, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.weight(1f))
            IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Remove", tint = p.muted, modifier = Modifier.size(18.dp)) }
        }
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        else -> "${m / 1440}d ago"
    }
}

private data class RoutinePreset(val label: String, val prompt: String, val hour: Int)

private val ROUTINES = listOf(
    RoutinePreset("Morning feed", FeedWriter.ROUTINE_PROMPT, 7),
    RoutinePreset("Morning brief", "Give me my morning brief: today's weather here, my calendar for today, the top 3 news stories with sources, and one thing to focus on from my goals.", 7),
    RoutinePreset("Evening wrap-up", "Evening wrap-up: what's on my calendar tomorrow, the weather tomorrow, and a one-line nudge about my goals.", 20),
    RoutinePreset("Inbox sweep", "Check my email for anything important from today and summarize it in 5 bullets or fewer. Don't change anything.", 17),
)

@Composable
private fun GoalsTab(g: Graph, startChat: (String) -> Unit) {
    val p = LocalPalette.current
    val goals by g.content.goals.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var routineDialog by remember { mutableStateOf<RoutinePreset?>(null) }
    var routines by remember { mutableStateOf(runCatching { g.scheduler.list() }.getOrDefault(emptyList())) }
    fun reload() { routines = runCatching { g.scheduler.list() }.getOrDefault(emptyList()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Goals", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${g.settings.current.persona.name} keeps these in mind and nudges you", color = p.muted, fontSize = 12.sp)
                }
                Button({ adding = true }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Add") }
            }
        }
        if (goals.isEmpty()) item { Text("No goals yet. Add one, like \"Save $5,000 by summer\" or \"Run a 5K\".", color = p.muted, fontSize = 13.sp) }
        items(goals, key = { it.id }) { goal -> GoalRow(goal, g, startChat) }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Routines", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Run every day by themselves; answers arrive as notifications.", color = p.muted, fontSize = 12.sp)
        }
        items(routines, key = { it.first }) { (id, label, whenText) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = p.text, fontSize = 15.sp)
                    Text(whenText, color = p.muted, fontSize = 12.sp)
                }
                TextButton({ g.scheduler.cancel(id); reload() }) { Text("Remove", color = p.bad) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (ROUTINES + RoutinePreset("Custom", "", 9)).forEach { r ->
                    Text("+ ${r.label}", color = p.text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(p.surface).clickable { routineDialog = r }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
    if (adding) GoalDialog({ title, note, cat -> g.content.addGoal(Goal(title = title.trim(), note = note.trim(), category = cat)); adding = false }) { adding = false }
    routineDialog?.let { preset ->
        RoutineDialog(preset, onSave = { label, prompt, hour, minute ->
            var first = LocalDateTime.now().with(LocalTime.of(hour, minute))
            if (first.isBefore(LocalDateTime.now())) first = first.plusDays(1)
            g.scheduler.add(prompt, label, first, daily = true)
            routineDialog = null
            reload()
        }) { routineDialog = null }
    }
}

@Composable
private fun GoalRow(goal: Goal, g: Graph, startChat: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ g.content.toggleGoal(goal.id) }) {
            Icon(if (goal.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, "Done", tint = if (goal.done) p.good else p.muted)
        }
        Column(Modifier.weight(1f)) {
            Text(goal.title, color = if (goal.done) p.muted else p.text, fontSize = 15.sp, textDecoration = if (goal.done) TextDecoration.LineThrough else null)
            Text(GOAL_CATEGORIES.firstOrNull { it.second == goal.category }?.first.orEmpty() + " " + goal.category + if (goal.note.isNotBlank()) " · ${goal.note}" else "", color = p.muted, fontSize = 12.sp)
        }
        IconButton({ startChat("Help me with my goal \"${goal.title}\"${if (goal.note.isNotBlank()) " (${goal.note})" else ""}. Make a simple plan with the next three steps.") }) {
            Icon(Icons.Default.ChatBubbleOutline, "Plan it", tint = p.muted, modifier = Modifier.size(18.dp))
        }
        IconButton({ g.content.deleteGoal(goal.id) }) { Icon(Icons.Default.DeleteOutline, "Remove", tint = p.muted, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun GoalDialog(onSave: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("Personal") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track a goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Goal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GOAL_CATEGORIES.forEach { (emoji, n) ->
                        val on = cat == n
                        Text("$emoji $n", color = if (on) Color(0xFF1A0E08) else p.text, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.surface2).clickable { cat = n }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSave(title, note, cat) }, enabled = title.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RoutineDialog(preset: RoutinePreset, onSave: (String, String, Int, Int) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(if (preset.label == "Custom") "" else preset.label) }
    var prompt by remember { mutableStateOf(preset.prompt) }
    var hour by remember { mutableIntStateOf(preset.hour) }
    var minute by remember { mutableIntStateOf(0) }
    val isFeed = preset.prompt == FeedWriter.ROUTINE_PROMPT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (preset.label == "Custom") "New routine" else preset.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!isFeed) OutlinedTextField(prompt, { prompt = it }, label = { Text("What should it do?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                else Text("Writes fresh posts into your feed every day.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every day at ")
                    TextButton({ hour = (hour + 23) % 24 }) { Text("−") }
                    Text("%d:%02d %s".format(if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM"), fontWeight = FontWeight.SemiBold)
                    TextButton({ hour = (hour + 1) % 24 }) { Text("+") }
                    TextButton({ minute = (minute + 15) % 60 }) { Text(":15") }
                }
            }
        },
        confirmButton = { TextButton({ onSave(label.trim(), prompt.trim(), hour, minute) }, enabled = label.isNotBlank() && prompt.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IdeasTab(g: Graph, startChat: (String) -> Unit) {
    val p = LocalPalette.current
    val categories = listOf("All") + IDEAS.map { it.category }.distinct()
    var selected by rememberSaveable { mutableStateOf("All") }
    val shown = if (selected == "All") IDEAS else IDEAS.filter { it.category == selected }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Ideas", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Things ${g.settings.current.persona.name} can do for you. Tap one to start.", color = p.muted, fontSize = 12.sp)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c ->
                    val on = selected == c
                    Text(c, color = if (on) Color(0xFF1A0E08) else p.text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.surface).clickable { selected = c }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }
        items(shown, key = { it.title }) { idea ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).clickable { startChat(idea.prompt) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(idea.emoji, fontSize = 26.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(idea.title, color = p.text, fontWeight = FontWeight.SemiBold)
                    Text(idea.prompt, color = p.muted, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}
