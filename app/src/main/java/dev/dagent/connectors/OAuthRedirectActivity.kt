package dev.dagent.connectors

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.dagent.DAgentApp
import dev.dagent.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the browser comes back after an OAuth sign-in (Google: `<package>:/oauth2redirect`,
 * MCP servers: `dagent://oauth`). It hands the redirect to the connector hub, which checks the
 * state, swaps the code for tokens (PKCE, no client secret) and keeps them in the SecretStore,
 * then returns to the app. Nothing from the URL is shown or stored besides that.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data?.toString()
        val hub = (application as DAgentApp).graph.connectorHub
        val app = applicationContext
        if (uri != null) {
            hub.scope.launch {
                val msg = hub.completeRedirect(uri)
                withContext(Dispatchers.Main) { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
            }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
