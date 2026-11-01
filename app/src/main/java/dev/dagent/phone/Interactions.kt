package dev.dagent.phone

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Questions the agent needs the person to answer on screen: "allow this?" and Android
 * permission prompts. The UI watches these flows and completes the requests.
 */
class Interactions(private val context: Context) {
    class Confirm(val title: String, val detail: String, val answer: CompletableDeferred<Boolean> = CompletableDeferred())
    class Permissions(val names: List<String>, val reason: String, val answer: CompletableDeferred<Boolean> = CompletableDeferred())

    private val _confirm = MutableStateFlow<Confirm?>(null)
    val confirm: StateFlow<Confirm?> = _confirm.asStateFlow()
    private val _permissions = MutableStateFlow<Permissions?>(null)
    val permissions: StateFlow<Permissions?> = _permissions.asStateFlow()

    /** Set by the activity while it is on screen. */
    @Volatile var uiVisible = false

    suspend fun confirm(title: String, detail: String): Boolean {
        if (!uiVisible) return false
        val req = Confirm(title, detail)
        _confirm.value = req
        val result = withTimeoutOrNull(180_000) { req.answer.await() } ?: false
        if (_confirm.value === req) _confirm.value = null
        return result
    }

    fun answerConfirm(req: Confirm, yes: Boolean) {
        req.answer.complete(yes)
        if (_confirm.value === req) _confirm.value = null
    }

    fun granted(vararg names: String) = names.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Asks for any missing permissions; true when all are granted afterwards. */
    suspend fun require(reason: String, vararg names: String): Boolean {
        if (granted(*names)) return true
        if (!uiVisible) return false
        val req = Permissions(names.filterNot { granted(it) }, reason)
        _permissions.value = req
        withTimeoutOrNull(120_000) { req.answer.await() }
        if (_permissions.value === req) _permissions.value = null
        return granted(*names)
    }

    fun permissionsDone(req: Permissions) {
        req.answer.complete(true)
        if (_permissions.value === req) _permissions.value = null
    }
}
