package dev.dagent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.clip
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import dev.dagent.core.llm.ImagePart
import dev.dagent.core.tools.WebFetchTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * A real browser the agent drives: a WebView it can load, read, click and type into. The chat
 * shows it live, and the person can take over when a page needs them (sign-in, captcha, payment).
 */
class BrowserAgent(private val context: Context) {
    data class PageState(val url: String = "", val title: String = "", val loading: Boolean = false, val active: Boolean = false)
    class HandOver(val reason: String, val done: CompletableDeferred<Unit> = CompletableDeferred())

    private val _view = MutableStateFlow(PageState())
    val view: StateFlow<PageState> = _view.asStateFlow()
    private val _handOver = MutableStateFlow<HandOver?>(null)
    val handOver: StateFlow<HandOver?> = _handOver.asStateFlow()

    private var web: WebView? = null
    private var loaded: CompletableDeferred<Unit>? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): WebView {
        web?.let { return it }
        val w = WebView(context)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                _view.value = _view.value.copy(url = url, loading = true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                _view.value = _view.value.copy(url = url, title = view.title.orEmpty(), loading = false)
                loaded?.complete(Unit)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                _view.value = _view.value.copy(title = title.orEmpty())
            }
        }
        // Give it a size even when it is not on screen, so pages lay out like on a phone.
        val dm = context.resources.displayMetrics
        w.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, dm.widthPixels, dm.heightPixels)
        web = w
        return w
    }

    fun close() {
        _view.value = _view.value.copy(active = false)
    }

    fun finishHandOver() {
        _handOver.value?.done?.complete(Unit)
        _handOver.value = null
    }

    private suspend fun <T> main(block: suspend (WebView) -> T): T = withContext(Dispatchers.Main) { block(webView()) }

    suspend fun navigate(url: String): String {
        val target = if (url.startsWith("http") || url.startsWith("about:")) url else "https://$url"
        main { w ->
            _view.value = _view.value.copy(active = true)
            loaded = CompletableDeferred()
            w.loadUrl(target)
        }
        waitLoaded()
        return summary()
    }

    private suspend fun waitLoaded() {
        withTimeoutOrNull(25_000) { loaded?.await() }
        delay(700) // let late scripts render
    }

    suspend fun js(script: String): String = main { w ->
        suspendCancellableCoroutine { cont -> w.evaluateJavascript(script) { cont.resume(it ?: "null") } }
    }

    /** evaluateJavascript returns JSON; this unwraps a string result. */
    private suspend fun jsString(script: String): String {
        val raw = js(script)
        return runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: raw
    }

    suspend fun summary(): String = "Page: ${_view.value.title}\nURL: ${_view.value.url}"

    suspend fun read(): String {
        val html = jsString("document.documentElement.outerHTML")
        return WebFetchTool.readable(html, _view.value.url, withLinks = false)
    }

    suspend fun elements(): String = jsString(ELEMENTS_JS)

    suspend fun click(target: String): String {
        val r = jsString(
            "(function(){var t=${q(target)};var el=document.querySelector('[data-dagent=\"'+t+'\"]');" +
                "if(!el){try{el=document.querySelector(t)}catch(e){}}" +
                "if(!el){var all=[...document.querySelectorAll('a,button,input[type=submit],input[type=button],[role=button],[onclick],summary,label')];" +
                "el=all.find(e=>(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().toLowerCase()===t.toLowerCase())||all.find(e=>(e.innerText||e.value||e.getAttribute('aria-label')||'').toLowerCase().includes(t.toLowerCase()));}" +
                "if(!el)return 'not found';el.scrollIntoView({block:'center'});el.click();return 'clicked '+(el.innerText||el.value||el.tagName).trim().slice(0,80);})()"
        )
        loaded = CompletableDeferred()
        withTimeoutOrNull(4_000) { loaded?.await() }
        delay(800)
        return "$r\n${summary()}"
    }

    suspend fun type(target: String, text: String, submit: Boolean): String {
        val r = jsString(
            "(function(){var t=${q(target)};var el=document.querySelector('[data-dagent=\"'+t+'\"]');" +
                "if(!el){try{el=document.querySelector(t)}catch(e){}}" +
                "if(!el){el=[...document.querySelectorAll('input,textarea,[contenteditable=true]')].find(e=>((e.placeholder||'')+(e.name||'')+(e.getAttribute('aria-label')||'')).toLowerCase().includes(t.toLowerCase()));}" +
                "if(!el)return 'field not found';el.focus();" +
                "if(el.isContentEditable){el.innerText=${q(text)};}else{var p=Object.getPrototypeOf(el);var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set){d.set.call(el,${q(text)})}else{el.value=${q(text)}}}" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));" +
                (if (submit) "if(el.form){if(el.form.requestSubmit)el.form.requestSubmit();else el.form.submit();}else{el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));}" else "") +
                "return 'typed into '+(el.name||el.placeholder||el.tagName);})()"
        )
        if (submit) {
            loaded = CompletableDeferred()
            withTimeoutOrNull(8_000) { loaded?.await() }
            delay(800)
        }
        return "$r\n${summary()}"
    }

    suspend fun scroll(down: Boolean): String {
        js("window.scrollBy(0, ${if (down) "window.innerHeight*0.8" else "-window.innerHeight*0.8"})")
        delay(400)
        return "Scrolled ${if (down) "down" else "up"}."
    }

    suspend fun back(): String {
        main { w -> if (w.canGoBack()) { loaded = CompletableDeferred(); w.goBack() } }
        waitLoaded()
        return summary()
    }

    suspend fun screenshot(): ImagePart = main { w ->
        val width = w.width.coerceAtLeast(1)
        val height = w.height.coerceAtLeast(1)
        val scale = (720f / width).coerceAtMost(1f)
        val bmp = Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        canvas.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
        w.draw(canvas)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        bmp.recycle()
        ImagePart("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    suspend fun handOver(reason: String): String {
        val req = HandOver(reason)
        withContext(Dispatchers.Main) { _view.value = _view.value.copy(active = true); _handOver.value = req }
        withTimeoutOrNull(15 * 60_000) { req.done.await() } ?: run { _handOver.value = null; return "The person did not finish within 15 minutes." }
        return "The person is done. " + summary()
    }

    private fun q(s: String) = JsonPrimitive(s).toString()

    companion object {
        /** Numbers the visible interactive elements so the agent can click "12" or type into "4". */
        private const val ELEMENTS_JS = """
            (function(){
              var sel='a[href],button,input,textarea,select,[role=button],[role=link],[role=tab],[role=menuitem],[onclick],summary,[contenteditable=true]';
              var out=[];var n=0;
              document.querySelectorAll('[data-dagent]').forEach(function(e){e.removeAttribute('data-dagent')});
              document.querySelectorAll(sel).forEach(function(e){
                var r=e.getBoundingClientRect();
                if(r.width<2||r.height<2)return;
                var st=getComputedStyle(e); if(st.visibility==='hidden'||st.display==='none')return;
                if(e.type==='hidden')return;
                n++; e.setAttribute('data-dagent',String(n));
                var label=(e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||e.title||e.name||'').replace(/\s+/g,' ').trim().slice(0,80);
                var kind=e.tagName.toLowerCase(); if(kind==='input')kind='input['+(e.type||'text')+']';
                var vis=(r.top<window.innerHeight&&r.bottom>0)?'':' (off screen)';
                out.push('['+n+'] '+kind+' '+label+(e.href?' -> '+e.href.slice(0,100):'')+vis);
                if(n>=150)return;
              });
              return out.join('\n')||'No interactive elements.';
            })()
        """
    }
}

class BrowserTool(private val browser: BrowserAgent, private val canSeeImages: () -> Boolean) : Tool {
    override val name = "browser"
    override val description = "Drive a real web browser (shown live to the person). Actions: open (url), read (page text), elements (numbered list of links, buttons and fields), " +
        "click (target: element number, CSS selector or visible text), type (target, text, submit), scroll (direction), back, screenshot (see the page), js (run JavaScript), " +
        "hand_over (reason: let the person do a sign-in, captcha, code or payment themselves, then continue). " +
        "Typical flow: open, elements, click or type, read. Never type passwords; hand over instead."
    override val parameters = schema {
        title()
        string("action", "What to do.", required = true, enum = listOf("open", "read", "elements", "click", "type", "scroll", "back", "screenshot", "js", "hand_over"))
        string("url", "For open.")
        string("target", "For click and type: element number from elements, a CSS selector, or visible text.")
        string("text", "For type.")
        boolean("submit", "For type: press Enter / submit the form after typing.")
        string("direction", "For scroll: up or down.", enum = listOf("up", "down"))
        string("script", "For js: JavaScript; the last expression's value is returned.")
        string("reason", "For hand_over: what the person needs to do, in a few words.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult = when (args.requireString("action")) {
        "open" -> ToolResult.ok(browser.navigate(args.requireString("url")) + "\n\n" + clip(browser.read(), 8_000))
        "read" -> ToolResult.ok(clip(browser.summary() + "\n\n" + browser.read(), 24_000))
        "elements" -> ToolResult.ok(browser.summary() + "\n" + clip(browser.elements(), 14_000))
        "click" -> ToolResult.ok(browser.click(args.requireString("target")))
        "type" -> ToolResult.ok(browser.type(args.requireString("target"), args.string("text") ?: "", args.bool("submit") == true))
        "scroll" -> ToolResult.ok(browser.scroll(args.string("direction") != "up"))
        "back" -> ToolResult.ok(browser.back())
        "screenshot" -> if (canSeeImages()) ToolResult(browser.summary(), images = listOf(browser.screenshot()))
            else ToolResult.ok("The current model cannot see images; use read or elements instead.")
        "js" -> ToolResult.ok(clip(browser.js(args.requireString("script")), 12_000))
        "hand_over" -> ToolResult.ok(browser.handOver(args.string("reason") ?: "Your turn"))
        else -> ToolResult.error("Unknown action")
    }
}
