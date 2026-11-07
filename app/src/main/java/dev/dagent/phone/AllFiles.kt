package dev.dagent.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Android's "All files access", which the phone_files tool needs to organize the phone's storage. */
object AllFiles {
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Null when allowed; otherwise what the person needs to do. */
    fun problem(context: Context): String? = if (granted(context)) null else
        "D-Agent can't see the phone's files yet. Ask the person to turn on All files access: Settings > Permissions > Your phone's files > Allow."

    fun open(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 30)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
