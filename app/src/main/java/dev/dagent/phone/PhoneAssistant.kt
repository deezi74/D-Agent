package dev.dagent.phone

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Lets D-Agent be picked as the phone's digital assistant (Settings > Apps > Default apps >
 * Digital assistant app). When it's the assistant, the phone's assist gesture (long-press the
 * power or home button, swipe up from a bottom corner, or a headset's voice button) opens
 * D-Agent straight into hands-free voice.
 *
 * It works through an activity-alias ([ALIAS]) that answers the system's ASSIST and VOICE_COMMAND
 * intents. Turning assistant mode off disables that alias, so D-Agent drops out of the phone's
 * assistant list and stops answering the gesture; turning it on brings it back. This doesn't
 * register a speech recognizer, so the phone's own speech recognition (which voice mode uses)
 * keeps working whichever assistant is picked.
 */
object PhoneAssistant {
    /** Class name of the activity-alias in the manifest (the namespace, not the debug package). */
    const val ALIAS = "dev.dagent.AssistantEntry"

    private fun component(context: Context) = ComponentName(context.packageName, ALIAS)

    /** True when assistant mode is on (the alias is enabled). It's on by default. */
    fun enabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /** Turns assistant mode on or off. Off removes D-Agent from the phone's assistant list. */
    fun setEnabled(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Whether D-Agent is the phone's assistant right now; null where the phone can't say (Android 9). */
    fun isDefault(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val rm = context.getSystemService(RoleManager::class.java) ?: return null
            if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) null else rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }.getOrNull()
    }

    /**
     * Opens the phone's settings where the assistant is picked. Android doesn't let an app make
     * itself the assistant, so the person picks it there. Returns false if no settings screen opened.
     */
    fun openPicker(context: Context): Boolean {
        val tries = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    /** True when the intent came from the phone's assist gesture or a voice-command button. */
    fun isAssistLaunch(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND
}
