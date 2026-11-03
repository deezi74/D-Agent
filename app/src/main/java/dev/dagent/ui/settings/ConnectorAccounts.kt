package dev.dagent.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.core.connectors.ConnectorState
import dev.dagent.core.connectors.ConnectorStatus
import dev.dagent.core.mcp.McpAuth
import dev.dagent.core.mcp.McpNames
import dev.dagent.core.mcp.McpServerConfig
import dev.dagent.core.oauth.OAuthClient
import dev.dagent.data.ConnectorHub
import dev.dagent.ui.LocalPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** "Connected" / "Needs sign-in" / "Error" with its reason, in the palette's colours. */
@Composable
internal fun StatusLine(s: ConnectorStatus) {
    val p = LocalPalette.current
    val color = when (s.state) {
        ConnectorState.CONNECTED -> p.good
        ConnectorState.ERROR -> p.bad
        else -> p.muted
    }
    Text(s.state.label + if (s.detail.isNotBlank()) " - ${s.detail}" else "", color = color, fontSize = 13.sp)
}

/** One line per connector with its status, so a broken one is easy to spot. */
@Composable
internal fun OverviewSection(g: Graph) {
    val hub = g.connectorHub
    val v by hub.version.collectAsState()
    val p = LocalPalette.current
    Section("Status")
    Card {
        v.hashCode() // read so the screen redraws when a status changes
        hub.registry.all().forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                StatusLine(hub.registry.status(c.id))
            }
        }
        val msg by hub.message.collectAsState()
        if (msg.isNotBlank()) Muted(msg)
    }
}

/** GitHub sign-in with the device flow (no browser redirect needed). */
@Composable
internal fun GitHubSignIn(g: Graph) {
    val hub = g.connectorHub
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by hub.version.collectAsState()
    var code by remember { mutableStateOf<OAuthClient.DeviceCode?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    v.hashCode() // read so the screen redraws when a status changes
    val signedIn = hub.signedIn(ConnectorHub.GITHUB)
    StatusLine(hub.registry.status(ConnectorHub.GITHUB))
    when {
        hub.githubClientId.isBlank() -> Muted("Sign in with GitHub isn't set up in this build (no GitHub client ID; see the README). Use a personal access token below instead.")
        signedIn -> Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("Signed in with GitHub. The token is kept encrypted on this phone.")
            TextButton({ hub.signOut(ConnectorHub.GITHUB); status = "Signed out." }) { Text("Sign out", color = p.bad) }
        }
        code != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val c = code!!
            Text("Enter this code on GitHub:", color = p.text, fontSize = 14.sp)
            Text(c.userCode, color = p.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("GitHub code", c.userCode))
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.verificationUri)))
                }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Copy and open GitHub") }
                TextButton({ job?.cancel(); code = null; status = "" }) { Text("Cancel") }
            }
            Muted("Waiting for you to approve...")
        }
        else -> Button({
            status = ""
            job = scope.launch {
                try {
                    val c = hub.githubDeviceStart()
                    code = c
                    hub.githubDeviceFinish(c)
                    status = "Signed in with GitHub."
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = "Sign-in failed: ${e.message}"
                } finally {
                    code = null
                }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Sign in with GitHub") }
    }
    if (status.isNotBlank()) Muted(status)
    Muted("Or paste a personal access token. A GitHub sign-in is used first when there is one.")
}

/** Gmail, Calendar and Drive over Google's APIs: read by default, drafts and events opt-in. */
@Composable
internal fun GoogleSection(g: Graph) {
    val hub = g.connectorHub
    val p = LocalPalette.current
    val v by hub.version.collectAsState()
    var write by remember { mutableStateOf(hub.googleWrite) }
    var status by remember { mutableStateOf("") }
    v.hashCode() // read so the screen redraws when a status changes
    Section("Google")
    Card {
        Muted("Search and read Gmail, see your calendar and find and read Drive files. It only asks Google for read access unless you allow drafts and events below. Mail and file text is treated as untrusted, like web pages.")
        StatusLine(hub.registry.status(ConnectorHub.GOOGLE))
        if (hub.googleClientId.isBlank()) {
            Muted("Not set up in this build: the developer needs to add a Google OAuth client ID (see the README).")
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ status = hub.startBrowserSignIn(ConnectorHub.GOOGLE) ?: "Finish signing in in the browser." },
                colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text(if (hub.signedIn(ConnectorHub.GOOGLE)) "Sign in again" else "Sign in with Google") }
            if (hub.signedIn(ConnectorHub.GOOGLE)) OutlinedButton({ hub.signOut(ConnectorHub.GOOGLE); status = "Signed out." }) { Text("Sign out") }
        }
        ToggleRow("Allow drafts and calendar events", "Asks Google for extra permission; you sign in again. It still asks you before each one.", write) { write = it; hub.googleWrite = it }
        if (status.isNotBlank()) Muted(status)
    }
}

/** MCP servers: add one, see its tools, switch servers and single tools on and off. */
@Composable
internal fun McpSection(g: Graph) {
    val hub = g.connectorHub
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val v by hub.version.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    v.hashCode() // read so the screen redraws when a status changes
    Section("MCP servers")
    Card {
        Muted("Add any Model Context Protocol server that speaks Streamable HTTP. Its tools show up as mcp_<server>_<tool>. Tools that only read run freely; anything else asks you first and is held when you're away.")
        hub.servers().forEach { s -> McpServerCard(g, s) }
        if (adding) AddMcpServer(g, onDone = { msg -> adding = false; status = msg }) else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ adding = true }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Add MCP server") }
            if (hub.servers().isNotEmpty()) OutlinedButton({ status = "Checking..."; scope.launch { hub.refreshMcp(force = true); status = "Updated." } }) { Text("Refresh tools") }
        }
        if (status.isNotBlank()) Muted(status)
    }
}

@Composable
private fun McpServerCard(g: Graph, s: McpServerConfig) {
    val hub = g.connectorHub
    val p = LocalPalette.current
    val id = ConnectorHub.MCP_PREFIX + s.id
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(s.url, color = p.muted, fontSize = 12.sp)
            }
            Switch(s.enabled, { hub.setServerEnabled(s.id, it) })
        }
        StatusLine(hub.registry.status(id))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.auth == McpAuth.OAUTH) {
                if (hub.signedIn(id)) TextButton({ hub.signOut(id) }) { Text("Sign out") }
                else TextButton({ hub.message.value = hub.startBrowserSignIn(id) ?: "Finish signing in in the browser." }) { Text("Sign in") }
            }
            val tools = hub.provider(s.id)?.discovered.orEmpty()
            if (tools.isNotEmpty()) TextButton({ open = !open }) { Text(if (open) "Hide tools" else "Tools (${tools.size})") }
            TextButton({ hub.removeServer(s.id) }) { Text("Remove", color = p.bad) }
        }
        if (open) hub.provider(s.id)?.discovered.orEmpty().forEach { t ->
            val risk = McpNames.risk(t)
            ToggleRow(McpNames.toolName(s.name, t.name), (if (risk.name == "READ") "Reads only. " else "Asks first. ") + t.description.take(140), t.name !in s.disabledTools) {
                hub.setToolEnabled(s.id, t.name, it)
            }
        }
    }
}

@Composable
private fun AddMcpServer(g: Graph, onDone: (String) -> Unit) {
    val hub = g.connectorHub
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var auth by remember { mutableStateOf(McpAuth.NONE) }
    var bearer by remember { mutableStateOf("") }
    var authorize by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it.trim() }, label = { Text("Server URL (https://.../mcp)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(McpAuth.NONE to "No sign-in", McpAuth.BEARER to "Token", McpAuth.OAUTH to "OAuth").forEach { (a, label) ->
                if (a == auth) Button({}, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text(label) }
                else OutlinedButton({ auth = a }) { Text(label) }
            }
        }
        when (auth) {
            McpAuth.BEARER -> OutlinedTextField(bearer, { bearer = it.trim() }, label = { Text("Bearer token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            McpAuth.OAUTH -> {
                Muted("Use the server's OAuth details. Register the redirect URI dagent://oauth with it; no client secret is used (PKCE).")
                OutlinedTextField(authorize, { authorize = it.trim() }, label = { Text("Authorize URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it.trim() }, label = { Text("Token URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(scopes, { scopes = it }, label = { Text("Scopes (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            McpAuth.NONE -> {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                error = ""
                runCatching {
                    hub.saveServer(McpServerConfig(id = "", name = name.trim().ifBlank { "server" }, url = url, auth = auth,
                        oauthAuthorizeUrl = authorize, oauthTokenUrl = token, oauthClientId = clientId, oauthScopes = scopes.trim()),
                        bearer = if (auth == McpAuth.BEARER) bearer else "")
                }.onSuccess { saved ->
                    if (auth == McpAuth.OAUTH) onDone(hub.startBrowserSignIn(ConnectorHub.MCP_PREFIX + saved.id) ?: "Finish signing in in the browser.")
                    else scope.launch { hub.refreshMcp(force = true); onDone("Added ${saved.name}.") }
                }.onFailure { error = it.message ?: "Couldn't add it." }
            }, enabled = url.length > 8 && (auth != McpAuth.OAUTH || (authorize.isNotBlank() && token.isNotBlank() && clientId.isNotBlank())),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Add") }
            TextButton({ onDone("") }) { Text("Cancel") }
        }
        if (error.isNotBlank()) Text(error, color = p.bad, fontSize = 13.sp)
    }
}
