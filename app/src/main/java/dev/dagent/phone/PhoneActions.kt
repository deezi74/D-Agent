package dev.dagent.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.dagent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Everything the agent can do with the phone itself. Used by the `phone` tool and by the
 * `phone` command inside Linux, so both behave the same.
 */
class PhoneActions(
    private val context: Context,
    private val ui: Interactions,
    private val confirmRisky: () -> Boolean,
    private val speak: (String) -> Unit,
    /** 0 = the phone's default SIM, 1 or 2 = that SIM slot. */
    private val defaultSim: () -> Int = { 0 },
) {
    data class Action(val name: String, val usage: String, val risky: Boolean = false)

    val actions = listOf(
        Action("notify", "title, text: show a notification"),
        Action("clipboard_get", "read the clipboard"),
        Action("clipboard_set", "text: copy text to the clipboard"),
        Action("open_url", "url: open a link in the phone's browser or the app that handles it"),
        Action("open_app", "name: open an installed app by name or package"),
        Action("list_apps", "query (optional): list installed apps"),
        Action("alarm", "time (e.g. 7:30 or 19:30), label, days (optional, e.g. mon,tue): set an alarm"),
        Action("timer", "seconds, label: start a countdown timer"),
        Action("calendar_events", "days (default 7), query (optional): read upcoming events"),
        Action("calendar_add", "title, start (YYYY-MM-DD HH:MM), end (optional), location, notes: add an event", risky = true),
        Action("contacts_search", "query: find people with their numbers and emails"),
        Action("sms_send", "to (name or number), text, sim (optional 1 or 2): send a text message right away", risky = true),
        Action("sms_compose", "to, text: open a text message ready to send"),
        Action("call", "to (name or number), sim (optional 1 or 2): place a phone call", risky = true),
        Action("sims", "list the SIM cards (slot, carrier, number)"),
        Action("email_compose", "to, subject, body: open an email ready to send"),
        Action("share", "text: open the share sheet with some text"),
        Action("location", "where the phone is (coordinates and address)"),
        Action("maps", "query, mode (search or navigate): open maps"),
        Action("battery", "battery level and charging state"),
        Action("flashlight", "on (true/false)"),
        Action("volume", "level (0-100): media volume"),
        Action("vibrate", "ms (default 300)"),
        Action("speak", "text: say something out loud"),
        Action("device_info", "model, Android version, storage, memory"),
    )

    fun help(): String = "phone actions:\n" + actions.joinToString("\n") { "  ${it.name}: ${it.usage}${if (it.risky) " (asks first)" else ""}" }

    suspend fun run(action: String, args: Map<String, String>): String {
        val a = actions.firstOrNull { it.name == action } ?: return if (action == "help" || action.isEmpty()) help() else "Unknown action \"$action\".\n" + help()
        if (a.risky && confirmRisky()) {
            val ok = ui.confirm(confirmTitle(action, args), args.entries.joinToString("\n") { "${it.key}: ${it.value}" })
            if (!ok) return "The person did not allow this ($action). Do not retry unless they ask."
        }
        return withContext(Dispatchers.Main.immediate) { dispatch(action, args) }
    }

    private fun confirmTitle(action: String, args: Map<String, String>) = when (action) {
        "sms_send" -> "Send a text to ${args["to"]}?"
        "call" -> "Call ${args["to"]}?"
        "calendar_add" -> "Add \"${args["title"]}\" to your calendar?"
        else -> "Allow $action?"
    }

    private suspend fun dispatch(action: String, a: Map<String, String>): String = when (action) {
        "notify" -> notify(a["title"] ?: "D-Agent", a["text"] ?: a["message"] ?: "")
        "clipboard_get" -> clipboard().primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { "Clipboard: $it" } ?: "The clipboard is empty."
        "clipboard_set" -> { clipboard().setPrimaryClip(ClipData.newPlainText("D-Agent", a["text"] ?: "")); "Copied to the clipboard." }
        "open_url" -> start(Intent(Intent.ACTION_VIEW, Uri.parse(need(a, "url"))), "Opened ${a["url"]}")
        "open_app" -> openApp(need(a, "name"))
        "list_apps" -> listApps(a["query"].orEmpty())
        "alarm" -> alarm(need(a, "time"), a["label"].orEmpty(), a["days"].orEmpty())
        "timer" -> start(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, need(a, "seconds").toDouble().toInt())
            .putExtra(AlarmClock.EXTRA_MESSAGE, a["label"].orEmpty())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Timer started for ${a["seconds"]} seconds.")
        "calendar_events" -> calendarEvents(a["days"]?.toIntOrNull() ?: 7, a["query"].orEmpty())
        "calendar_add" -> calendarAdd(a)
        "contacts_search" -> contacts(need(a, "query")).ifEmpty { listOf("No contacts match \"${a["query"]}\".") }.joinToString("\n")
        "sms_send" -> smsSend(need(a, "to"), need(a, "text"), simSlot(a["sim"]))
        "sms_compose" -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(resolveNumber(a["to"].orEmpty()) ?: a["to"].orEmpty())))
            .putExtra("sms_body", a["text"].orEmpty()), "Opened a text to ${a["to"]}; the person taps Send.")
        "call" -> call(need(a, "to"), simSlot(a["sim"]))
        "sims" -> simsText()
        "email_compose" -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(a["to"].orEmpty())))
            .putExtra(Intent.EXTRA_SUBJECT, a["subject"].orEmpty()).putExtra(Intent.EXTRA_TEXT, a["body"].orEmpty()), "Opened an email draft.")
        "share" -> start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, need(a, "text")), "Share"), "Opened the share sheet.")
        "location" -> locationText()
        "maps" -> maps(need(a, "query"), a["mode"] ?: "search")
        "battery" -> battery()
        "flashlight" -> flashlight(a["on"]?.lowercase() != "false")
        "volume" -> volume(need(a, "level").toInt())
        "vibrate" -> vibrate(a["ms"]?.toLongOrNull() ?: 300)
        "speak" -> { speak(need(a, "text")); "Speaking." }
        "device_info" -> deviceInfo()
        else -> help()
    }

    private fun need(a: Map<String, String>, key: String) = a[key]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing \"$key\"")

    private fun clipboard() = context.getSystemService(ClipboardManager::class.java)

    private fun start(intent: Intent, done: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            done
        } catch (e: Exception) {
            "No app on this phone can do that (${e.message})."
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun notify(title: String, text: String): String {
        if (Build.VERSION.SDK_INT >= 33 && !ui.require("to show notifications", Manifest.permission.POST_NOTIFICATIONS)) return "Notifications are not allowed."
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100000).toInt(), n)
        return "Notification shown."
    }

    private fun openApp(name: String): String {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage(name)?.let { return start(it, "Opened $name.") }
        val apps = launchable()
        val match = apps.firstOrNull { it.first.equals(name, true) } ?: apps.firstOrNull { it.first.contains(name, true) }
            ?: return "No installed app called \"$name\". Use list_apps to see what is installed."
        return pm.getLaunchIntentForPackage(match.second)?.let { start(it, "Opened ${match.first}.") } ?: "Can't open ${match.first}."
    }

    private fun launchable(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun listApps(query: String) = launchable().filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
        .joinToString("\n") { "${it.first} (${it.second})" }.ifEmpty { "No apps match." }

    private fun alarm(time: String, label: String, days: String): String {
        val t = parseTime(time) ?: return "Couldn't read the time \"$time\". Use 7:30 or 19:30."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, t.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val dayMap = mapOf("sun" to 1, "mon" to 2, "tue" to 3, "wed" to 4, "thu" to 5, "fri" to 6, "sat" to 7)
        val list = days.lowercase().split(',', ' ').mapNotNull { d -> dayMap[d.trim().take(3)] }
        if (list.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(list))
        return start(intent, "Alarm set for ${t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}${if (label.isNotBlank()) " ($label)" else ""}.")
    }

    private fun parseTime(s: String): LocalTime? {
        val t = s.trim().uppercase().replace(".", "")
        val patterns = listOf("H:mm", "HH:mm", "h:mm a", "h:mma", "h a", "ha", "H")
        for (p in patterns) runCatching { return LocalTime.parse(t, DateTimeFormatter.ofPattern(p, Locale.US)) }
        return null
    }

    private suspend fun calendarEvents(days: Int, query: String): String {
        if (!ui.require("to read your calendar", Manifest.permission.READ_CALENDAR)) return "Calendar access was not allowed."
        val now = System.currentTimeMillis()
        val end = now + days.coerceIn(1, 365) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val fmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
        val out = mutableListOf<String>()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null, null, CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 60) {
                val title = c.getString(0) ?: "(no title)"
                if (query.isNotBlank() && !title.contains(query, true)) continue
                val begin = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(c.getLong(1)), ZoneId.systemDefault())
                val loc = c.getString(3).orEmpty()
                val allDay = c.getInt(4) == 1
                out += "- ${if (allDay) begin.toLocalDate().toString() + " (all day)" else begin.format(fmt)}: $title${if (loc.isNotBlank()) " @ $loc" else ""}"
            }
        }
        return if (out.isEmpty()) "No events in the next $days days." else out.joinToString("\n")
    }

    private suspend fun calendarAdd(a: Map<String, String>): String {
        if (!ui.require("to add calendar events", Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)) return "Calendar access was not allowed."
        val start = parseDateTime(need(a, "start")) ?: return "Couldn't read the start \"${a["start"]}\". Use YYYY-MM-DD HH:MM."
        val end = a["end"]?.let { parseDateTime(it) } ?: start.plusHours(1)
        val calId = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            null, null, null,
        )?.use { c ->
            var best: Long? = null
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (best == null || c.getInt(1) == 1) best = c.getLong(0)
            }
            best
        } ?: return "No writable calendar on this phone. Sign in to a calendar account first."
        val zone = ZoneId.systemDefault()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, need(a, "title"))
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            a["location"]?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            a["notes"]?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return "The calendar refused the event."
        return "Added \"${a["title"]}\" on ${start.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US))}."
    }

    private fun parseDateTime(s: String): LocalDateTime? {
        val t = s.trim().replace('T', ' ')
        val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd H:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd h:mm a", "MM/dd/yyyy HH:mm", "MM/dd/yyyy h:mm a")
        for (p in patterns) runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p, Locale.US)) }
        runCatching { return LocalDate.parse(t).atTime(9, 0) }
        return null
    }

    private suspend fun contacts(query: String): List<String> {
        if (!ui.require("to look up contacts", Manifest.permission.READ_CONTACTS)) return listOf("Contacts access was not allowed.")
        val out = linkedMapOf<String, MutableList<String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
            arrayOf("%$query%", "%$query%"), null,
        )?.use { c -> while (c.moveToNext() && out.size < 30) out.getOrPut(c.getString(0) ?: "?") { mutableListOf() } += "phone ${c.getString(1)}" }
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$query%"), null,
        )?.use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0) ?: "?") { mutableListOf() } += "email ${c.getString(1)}" }
        return out.map { (name, items) -> "$name: ${items.distinct().joinToString(", ")}" }
    }

    /** A number as given, or the first number of the best matching contact. */
    private fun resolveNumber(to: String): String? {
        if (to.any { it.isDigit() } && to.count { it.isDigit() } >= 3) return to
        if (!ui.granted(Manifest.permission.READ_CONTACTS)) return null
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$to%"), null,
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    // ---- SIM cards ----------------------------------------------------------------------------

    /** "2", "sim 2", "SIM2" -> 2; anything else falls back to the setting (0 = phone default). */
    private fun simSlot(arg: String?): Int = arg?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..4 } ?: defaultSim()

    @SuppressLint("MissingPermission")
    private suspend fun activeSims(): List<SubscriptionInfo> {
        if (!ui.require("to see your SIM cards", Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching { sm.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex } }.getOrDefault(emptyList())
    }

    private suspend fun simsText(): String {
        val sims = activeSims()
        if (sims.isEmpty()) return "No SIM cards found (or the phone-state permission was not allowed)."
        return sims.joinToString("\n") { s ->
            @Suppress("DEPRECATION") val num = runCatching { s.number }.getOrNull().orEmpty()
            "SIM ${s.simSlotIndex + 1}: ${s.carrierName ?: s.displayName}${if (num.isNotBlank()) " ($num)" else ""}"
        } + "\nDefault for calls and texts in D-Agent: " + (if (defaultSim() == 0) "the phone's default" else "SIM ${defaultSim()}")
    }

    private suspend fun simFor(slot: Int): SubscriptionInfo? =
        if (slot <= 0) null else activeSims().firstOrNull { it.simSlotIndex == slot - 1 }

    private suspend fun smsSend(to: String, text: String, slot: Int): String {
        if (!ui.require("to send texts", Manifest.permission.SEND_SMS)) return "SMS permission was not allowed. Use sms_compose instead."
        if (!to.any { it.isDigit() }) ui.require("to find the contact", Manifest.permission.READ_CONTACTS)
        val number = resolveNumber(to) ?: return "Couldn't find a number for \"$to\"."
        val sim = simFor(slot)
        if (slot > 0 && sim == null) return "There is no SIM $slot in this phone. Use the sims action to see the cards."
        val sms = when {
            sim != null && Build.VERSION.SDK_INT >= 31 -> context.getSystemService(SmsManager::class.java).createForSubscriptionId(sim.subscriptionId)
            sim != null -> @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sim.subscriptionId)
            Build.VERSION.SDK_INT >= 31 -> context.getSystemService(SmsManager::class.java)
            else -> @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        val parts = sms.divideMessage(text)
        sms.sendMultipartTextMessage(number, null, parts, null, null)
        return "Text sent to $to ($number)" + (if (sim != null) " from SIM $slot." else ".")
    }

    @SuppressLint("MissingPermission")
    private suspend fun call(to: String, slot: Int): String {
        if (!to.any { it.isDigit() }) ui.require("to find the contact", Manifest.permission.READ_CONTACTS)
        val number = resolveNumber(to) ?: return "Couldn't find a number for \"$to\"."
        return if (ui.require("to place calls", Manifest.permission.CALL_PHONE)) {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
            var via = ""
            if (slot > 0) {
                val handle = phoneAccountFor(slot)
                    ?: return "There is no SIM $slot ready for calls in this phone. Use the sims action to see the cards."
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                // Some phone makers read these instead of the account handle.
                intent.putExtra("com.android.phone.extra.slot", slot - 1).putExtra("simSlot", slot - 1).putExtra("slot", slot - 1)
                via = " on SIM $slot"
            }
            start(intent, "Calling $to ($number)$via.")
        } else {
            start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))), "Opened the dialer for $number; the person taps Call.")
        }
    }

    /** The calling account that belongs to SIM [slot], matched by subscription id. */
    @SuppressLint("MissingPermission")
    private suspend fun phoneAccountFor(slot: Int): PhoneAccountHandle? {
        val sim = simFor(slot) ?: return null
        val tm = context.getSystemService(TelecomManager::class.java) ?: return null
        val accounts = runCatching { tm.callCapablePhoneAccounts }.getOrDefault(emptyList())
        val tel = context.getSystemService(android.telephony.TelephonyManager::class.java)
        return accounts.firstOrNull { h ->
            val subId = if (Build.VERSION.SDK_INT >= 30) runCatching { tel.getSubscriptionId(h) }.getOrDefault(-1) else -1
            subId == sim.subscriptionId || h.id == sim.subscriptionId.toString() || h.id == sim.iccId
        } ?: accounts.getOrNull(slot - 1)
    }

    private fun maps(query: String, mode: String): String {
        val uri = if (mode == "navigate") Uri.parse("google.navigation:q=" + Uri.encode(query)) else Uri.parse("geo:0,0?q=" + Uri.encode(query))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return if (intent.resolveActivity(context.packageManager) != null) start(intent, "Opened maps for $query.")
        else start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query))), "Opened maps in the browser for $query.")
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ask: Boolean): Location? {
        val perms = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (!ui.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (!ask || !ui.require("to know where you are", *perms)) return null
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) return last
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = when {
                LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                else -> return last
            }
            val fresh = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                }
            }
            return fresh ?: last
        }
        return last
    }

    suspend fun placeName(loc: Location): String = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            val list = Geocoder(context, Locale.US).getFromLocation(loc.latitude, loc.longitude, 1)
            val a = list?.firstOrNull() ?: return@runCatching ""
            listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryCode).joinToString(", ")
        }.getOrDefault("")
    }

    private suspend fun locationText(): String {
        val loc = currentLocation(ask = true) ?: return "Location is off or not allowed."
        val place = placeName(loc)
        val addr = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.US).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0)
            }.getOrNull()
        }
        return "Latitude ${"%.5f".format(loc.latitude)}, longitude ${"%.5f".format(loc.longitude)} (accuracy about ${loc.accuracy.toInt()} m)" +
            (addr?.let { "\nAddress: $it" } ?: if (place.isNotBlank()) "\nArea: $place" else "")
    }

    fun battery(): String {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "$level%${if (charging) ", charging" else ""}"
    }

    private fun flashlight(on: Boolean): String {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return "Flashlight ${if (on) "on" else "off"}."
    }

    private fun volume(level: Int): String {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0, 100) * max / 100.0).toInt(), 0)
        return "Media volume set to ${level.coerceIn(0, 100)}%."
    }

    private fun vibrate(ms: Long): String {
        val v = context.getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(10, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
        return "Buzzed."
    }

    fun deviceInfo(): String {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "RAM ${"%.1f".format(mem.totalMem / 1e9)} GB (${"%.1f".format(mem.availMem / 1e9)} GB free), " +
            "storage ${"%.1f".format(stat.availableBytes / 1e9)} GB free, battery ${battery()}"
    }

    companion object {
        const val CHANNEL_AGENT = "agent"
        const val CHANNEL_WORK = "work"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_AGENT) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_AGENT, "Messages from your agent", NotificationManager.IMPORTANCE_HIGH))
            }
            if (nm.getNotificationChannel(CHANNEL_WORK) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_WORK, "Agent working", NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun launchIntent(context: Context): android.app.PendingIntent = android.app.PendingIntent.getActivity(
            context, 0,
            Intent(context, dev.dagent.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
