@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.dagent.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.connectors.EmailAccount
import dev.dagent.connectors.EmailClient
import dev.dagent.connectors.TermuxBridge
import dev.dagent.core.tools.GitHubClient
import dev.dagent.ui.LocalPalette
import kotlinx.coroutines.launch

/** Status of every connector, email accounts, GitHub, Google, MCP servers, Telegram bot, Termux and places, each with its own key and switches. */
@Composable
fun ConnectorsPage(g: Graph, back: () -> Unit) {
    Page("Connectors", back) {
        Muted("Connect your own accounts. Keys stay encrypted on this phone, and nothing here needs a D-Agent account.")
        DetailsSection(g)
        OverviewSection(g)
        PcSection(g)
        CallsSection(g)
        EmailSection(g)
        GitHubSection(g)
        GoogleSection(g)
        McpSection(g)
        TelegramSection(g)
        TermuxSection(g)
        PlacesSection(g)
    }
}

@Composable
private fun DetailsSection(g: Graph) {
    var name by remember { mutableStateOf(g.connectors.myName) }
    var phone by remember { mutableStateOf(g.connectors.myPhone) }
    var email by remember { mutableStateOf(g.connectors.myEmail) }
    var address by remember { mutableStateOf(g.connectors.myAddress) }
    Section("My details")
    Card {
        Muted("Used to fill in delivery and contact forms when ${g.settings.current.persona.name} orders for you, and to say who a call is for. It pays with the card already saved on each site and asks you to OK every order first. It never types card numbers or passwords.")
        OutlinedTextField(name, { name = it; g.connectors.myName = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it; g.connectors.myPhone = it }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it; g.connectors.myEmail = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it; g.connectors.myAddress = it }, label = { Text("Home address (also used for weather and places)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

private const val PC_INSTALL_WINDOWS = "irm https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.ps1 | iex"
private const val PC_INSTALL_LINUX = "curl -fsSL https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.sh | bash"

@Composable
private fun PcSection(g: Graph) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(g.connectors.pcUrl) }
    var code by remember { mutableStateOf(g.connectors.pcCode) }
    var status by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var model by remember { mutableStateOf("") }
    fun copy(text: String, what: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText(what, text))
        status = "Copied the $what command. Paste it on the computer."
    }
    var where by remember { mutableStateOf(if (url.isBlank() || dev.dagent.connectors.LocalPc.isLocal(url)) "phone" else "pc") }
    var busy by remember { mutableStateOf(false) }
    Section("Always-on computer")
    Card {
        Muted("Big jobs (research, coding, downloads) run on an always-on computer, keep going while you use other apps or the screen is off, and you get a notification when they're done. Ask things like \"in the background, research the best 65-inch TVs and save a report\". Use this phone through Termux, or a PC or laptop.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("phone" to "This phone", "pc" to "A PC or laptop").forEach { (id, label) ->
                FilterChip(selected = where == id, onClick = { where = id; status = "" }, label = { Text(label) })
            }
        }
        if (where == "phone") {
            Muted("Runs D-Agent PC inside Termux on this phone, only reachable by apps on this phone. Needs Termux from F-Droid and the Termux connector below turned on. Add Termux:Boot from F-Droid to start it when the phone turns on.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    busy = true
                    scope.launch {
                        status = dev.dagent.connectors.LocalPc.install(g) { status = it }
                        url = g.connectors.pcUrl; code = g.connectors.pcCode
                        busy = false
                    }
                }, enabled = !busy) { Text(if (dev.dagent.connectors.LocalPc.isLocal(url) && code.isNotBlank()) "Update" else "Set up on this phone") }
                if (dev.dagent.connectors.LocalPc.isLocal(url) && code.isNotBlank()) OutlinedButton({
                    busy = true
                    scope.launch {
                        status = if (dev.dagent.connectors.LocalPc.ensureRunning(g)) "Running on this phone." else "It didn't start. Check the Termux connector below, or in Termux run: bash ~/.dagent-pc/start.sh"
                        busy = false
                    }
                }, enabled = !busy) { Text("Start") }
            }
        } else {
            Text("1. On the computer, install D-Agent PC:", color = p.text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ copy(PC_INSTALL_WINDOWS, "Windows (PowerShell)") }) { Text("Copy Windows command") }
                OutlinedButton({ copy(PC_INSTALL_LINUX, "Linux/Mac") }) { Text("Linux/Mac") }
            }
            Muted("Send the command to the computer (email or a notes app), paste it into PowerShell (Windows) or a terminal (Linux, Mac), and press Enter. It prints an address and a pairing code. It only listens on that computer until you run it once with --lan (python ~/.dagent-pc/dagent_pc.py --lan). Plain http:// only works to this phone, so a computer on your network needs an https:// address (for example Tailscale HTTPS). An old laptop with 4 GB works fine, since the AI can run somewhere else.")
            Text("2. Enter what it shows:", color = p.text, fontSize = 14.sp)
            OutlinedTextField(url, { url = it; g.connectors.pcUrl = it }, label = { Text("Address (e.g. 192.168.1.20)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it; g.connectors.pcCode = it }, label = { Text("Pairing code") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                status = "Checking..."
                scope.launch {
                    status = runCatching {
                        val h = dev.dagent.connectors.PcWatch.client(g).health()
                        fun s(o: kotlinx.serialization.json.JsonObject?, k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                        val llm = h["llm"] as? kotlinx.serialization.json.JsonObject
                        models = (llm?.get("models") as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
                        model = s(llm, "model")
                        val problem = s(llm, "problem")
                        "Connected to ${s(h, "name")} (${s(h, "os")}). " +
                            if (problem.isNotBlank()) "But its AI isn't answering: $problem" else "AI model: ${model.ifBlank { "none loaded yet" }}."
                    }.getOrElse { it.message ?: "Couldn't connect." }
                }
            }) { Text("Test") }
            OutlinedButton({
                status = "Updating..."
                scope.launch { status = dev.dagent.connectors.LocalPc.useAppAi(g) }
            }) { Text("Use the app's AI") }
        }
        if (models.isNotEmpty()) {
            Text("Model it uses for tasks:", color = p.text, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                models.forEach { m ->
                    val on = m == model
                    Text(m, color = if (on) Color(0xFF1A0E08) else p.text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.bg).clickable {
                            model = m
                            scope.launch { status = runCatching { dev.dagent.connectors.PcWatch.client(g).setModel(null, m, null); "The PC will use $m." }.getOrElse { it.message.orEmpty() } }
                        }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        if (status.isNotBlank()) Muted(status)
        if (where == "pc") Muted("Away from home? Install Tailscale on the PC and the phone and use the PC's Tailscale address. Never open port 8787 on your router.")
    }
}

@Composable
private fun CallsSection(g: Graph) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(g.connectors.aiCallKey) }
    Section("AI phone calls")
    Card {
        Muted("Let ${g.settings.current.persona.name} call a place and talk for you (\"call Waffle House and ask if they have eggs\"), then tell you what they said. Android doesn't let apps speak into a normal SIM call, so the call goes out from Bland AI's line using your own key. Calls cost about 9 cents a minute on their side. It always says it's an AI assistant and asks you before every call.")
        OutlinedTextField(key, { key = it.trim(); g.connectors.aiCallKey = key }, label = { Text("Bland AI API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.bland.ai"))) }) { Text("Get a key") }
    }
}

@Composable
private fun EmailSection(g: Graph) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(g.connectors.emailAccounts) }
    var preset by remember { mutableStateOf(EmailClient.presets.first()) }
    var host by remember { mutableStateOf(preset.host) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var organize by remember { mutableStateOf(g.connectors.emailOrganize) }
    Section("Email")
    Card {
        Muted("Read, search and tidy your mail over IMAP with an app password. It never sends mail or deletes for good; trash stays recoverable.")
        accounts.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.user, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton({ scope.launch { status = runCatching { g.email.test(a) }.getOrElse { "${a.user}: ${it.message}" } } }) { Text("Test") }
                TextButton({ accounts = accounts - a; g.connectors.emailAccounts = accounts }) { Text("Remove", color = p.bad) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EmailClient.presets.forEach { pr ->
                val on = pr.id == preset.id
                Text(pr.label, color = if (on) Color(0xFF1A0E08) else p.text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.bg)
                        .clickable { preset = pr; host = pr.host }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        Muted(preset.help)
        if (preset.id == "gmail") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/signinoptions/twosv"))) }) { Text("1. 2-Step Verification") }
            OutlinedButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) }) { Text("2. App password") }
        }
        if (preset.id == "other") OutlinedTextField(host, { host = it }, label = { Text("IMAP server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it.trim() }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("App password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button({
            val acc = EmailAccount(host.trim(), preset.port, user.trim(), cleanPassword(pass, preset.id))
            status = "Checking..."
            scope.launch {
                runCatching { g.email.test(acc) }
                    .onSuccess { msg -> accounts = accounts.filterNot { it.user.equals(acc.user, true) } + acc; g.connectors.emailAccounts = accounts; user = ""; pass = ""; status = msg }
                    .onFailure { status = "Couldn't sign in. ${it.message}" }
            }
        }, enabled = user.contains('@') && pass.isNotBlank() && host.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Add account") }
        if (status.isNotBlank()) Muted(status)
        ToggleRow("Let it organize", "Mark read, archive and move to trash when you ask", organize) { organize = it; g.connectors.emailOrganize = it }
    }
}

@Composable
private fun GitHubSection(g: Graph) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(g.connectors.githubToken) }
    var write by remember { mutableStateOf(g.connectors.githubWrite) }
    var status by remember { mutableStateOf("") }
    Section("GitHub")
    Card {
        Muted("Repos, issues, pull requests, notifications and files, plus the app builder: it makes a private repo for each app and builds a ready-to-install APK. Sign in with GitHub, or make a classic token at github.com > Settings > Developer settings with the repo and workflow boxes ticked (a fine-grained token needs Administration, Contents and Actions).")
        GitHubSignIn(g)
        OutlinedTextField(token, { token = it.trim() }, label = { Text("Personal access token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                g.connectors.githubToken = token
                scope.launch { status = runCatching { "Connected as " + GitHubClient({ token }, http = g.http).whoAmI() }.getOrElse { it.message ?: "Failed" } }
            }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Save and test") }
            OutlinedButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens/new?scopes=repo,workflow&description=D-Agent"))) }) { Text("Get a token") }
        }
        if (status.isNotBlank()) Muted(status)
        ToggleRow("Allow opening issues and comments", "Off: read only", write) { write = it; g.connectors.githubWrite = it }
    }
}

@Composable
private fun TelegramSection(g: Graph) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val status by g.telegram.status.collectAsState()
    var token by remember { mutableStateOf(g.telegram.token) }
    var check by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(g.telegram.running) }
    Section("Telegram")
    Card {
        Muted("Talk to ${g.settings.current.persona.name} from Telegram on any device. In Telegram, message @BotFather, send /newbot, and paste the token it gives you.")
        OutlinedTextField(token, { token = it.trim() }, label = { Text("Bot token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                g.telegram.token = token
                scope.launch { check = runCatching { "Bot found: " + g.telegram.test(token) }.getOrElse { it.message ?: "Failed" } }
            }, enabled = token.isNotBlank()) { Text("Test") }
            Button({
                g.telegram.token = token
                if (on) g.telegram.stop() else g.telegram.start()
                on = !on
            }, enabled = token.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text(if (on) "Turn off" else "Turn on") }
        }
        if (check.isNotBlank()) Muted(check)
        Text("Status: $status", color = p.text, fontSize = 13.sp)
        if (!g.telegram.paired) Text("Pairing code: ${g.telegram.pairingCode}  (send /start ${g.telegram.pairingCode} to your bot)", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        else TextButton({ g.telegram.unpair() }) { Text("Unpair this chat") }
        Muted("Send /new in Telegram to start a fresh conversation.")
    }
}

@Composable
private fun TermuxSection(g: Graph) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var on by remember { mutableStateOf(g.connectors.termuxOn) }
    var allowed by remember { mutableStateOf(g.termux.hasPermission()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    Section("Termux")
    Card {
        Muted("Lets ${g.settings.current.persona.name} run commands in your own Termux app: start FreeLLMAPI, run scripts, set up builds. (It also has its own built-in Linux computer.)")
        if (!g.termux.isInstalled()) {
            Text("Termux isn't installed.", color = p.bad, fontSize = 13.sp)
            OutlinedButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))) }) { Text("Get Termux (F-Droid)") }
        } else {
            Muted("1. In Termux run: mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (allowed) "2. Permission granted" else "2. Allow D-Agent to run commands in Termux", color = if (allowed) p.good else p.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (!allowed) TextButton({ ask.launch(TermuxBridge.PERMISSION) }) { Text("Allow") }
            }
            ToggleRow("3. Use Termux", "Adds the termux tool", on) { on = it; g.connectors.termuxOn = it }
            if (g.termux.isOutdated()) Text("This is the old Play Store Termux, which ignores other apps. Install Termux from F-Droid.", color = p.bad, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Termux setup", "mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings"))
                    context.packageManager.getLaunchIntentForPackage("com.termux")?.let { context.startActivity(it) }
                    status = "Setup command copied. In Termux, long-press, Paste, then Enter."
                }) { Text("Copy step 1 & open Termux") }
            }
            Button({
                status = "Starting FreeLLMAPI..."
                scope.launch {
                    status = dev.dagent.connectors.TermuxTool(g.termux) { true }
                        .startFreeLlmApi(object : dev.dagent.core.agent.ToolContext { override val sessionId = "" }).text
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Start FreeLLMAPI") }
            if (status.isNotBlank()) Muted(status)
        }
    }
}

@Composable
private fun PlacesSection(g: Graph) {
    var key by remember { mutableStateOf(g.connectors.googlePlacesKey) }
    Section("Weather and places")
    Card {
        Muted("Weather (Open-Meteo) and nearby places (OpenStreetMap) work with no key. A Google Places key adds ratings and live open/closed status.")
        OutlinedTextField(key, { key = it.trim(); g.connectors.googlePlacesKey = key }, label = { Text("Google Places key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Pasted app passwords often carry invisible characters (non-breaking or zero-width spaces from
 * Google's page). Gmail app passwords are exactly 16 letters, so keep only letters for Gmail;
 * for others drop every kind of whitespace.
 */
internal fun cleanPassword(raw: String, provider: String): String {
    val noSpace = raw.filterNot { it.isWhitespace() || it == '\u00A0' || it == '\u200B' || it == '\u200C' || it == '\u200D' || it == '\u2060' || it == '\uFEFF' }
    return if (provider == "gmail") noSpace.filter { it.isLetter() }.lowercase() else noSpace
}
