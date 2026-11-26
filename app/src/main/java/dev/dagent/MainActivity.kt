package dev.dagent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import dev.dagent.phone.PhoneAssistant
import dev.dagent.ui.AppRoot
import dev.dagent.ui.DAgentTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as DAgentApp).graph

    /** Text or a photo shared into the app from elsewhere; the chat composer picks it up. */
    val shared = mutableStateOf<Pair<String, Uri?>?>(null)

    /** Goes up by one each time the phone's assist gesture opens D-Agent; the app opens voice mode. */
    val assist = mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        graph.interactions.permissions.value?.let { graph.interactions.permissionsDone(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        if (savedInstanceState == null) handleAssist(intent)
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }
        setContent {
            val prefs by graph.settings.state.collectAsState()
            val permReq by graph.interactions.permissions.collectAsState()
            LaunchedEffect(permReq) {
                permReq?.let { permissionLauncher.launch(it.names.toTypedArray()) }
            }
            DAgentTheme(prefs.accent) {
                AppRoot(graph, shared, assist)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        handleAssist(intent)
    }

    override fun onResume() {
        super.onResume()
        graph.interactions.uiVisible = true
    }

    override fun onPause() {
        graph.interactions.uiVisible = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Optional: give the on-phone model's memory back when the app is in the background.
        if (graph.settings.current.localUnloadInBackground && !graph.runner.state.value.running && !isChangingConfigurations) graph.local.stop()
    }

    private fun handleAssist(intent: Intent?) {
        if (PhoneAssistant.isAssistLaunch(intent) && PhoneAssistant.enabled(this)) assist.intValue++
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        @Suppress("DEPRECATION")
        val image = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        shared.value = text to image
    }
}
