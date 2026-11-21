package com.music.bitchord.presence

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Spotify's web player in a WebView of this app: the page that plays the
 * track, muted, so that Spotify shows it as the listener's activity.
 *
 * It presents itself as desktop Chrome (the mobile page has no player), is
 * signed in with the app's Spotify cookie, and is allowed DRM-protected media.
 * Everything here runs on the main thread. It has no window of its own; the
 * log screen can borrow it to show what the page looks like.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class WebPlayer(private val appContext: Context) {

    /** What the page says about its player right now. */
    class WebState(
        val title: String,
        val artist: String,
        val sessionState: String,
        val playing: Boolean,
        val seconds: Double,
        val nowPlaying: String,
        val loggedOut: Boolean,
        val page: String,
    ) {
        /** The track the page names: the media session first, then the "now playing" label. */
        val shown: String get() = title.ifBlank { nowPlaying }

        override fun toString() =
            "playing=$playing, session=${sessionState.ifBlank { "-" }}, t=$seconds, \"${shown.take(60)}\", loggedOut=$loggedOut, ${page.take(40)}"
    }

    private val wrapper = MutableContextWrapper(appContext)
    val view: WebView = WebView(wrapper)
    private var loading: CompletableDeferred<Boolean>? = null
    private var destroyed = false

    /** Called when the page's process died (low memory, a crash): the player must be built again. */
    var onRenderGone: (() -> Unit)? = null

    val userAgent: String

    init {
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        val chrome = Regex("""Chrome/([\d.]+)""").find(settings.userAgentString)?.groupValues?.get(1) ?: "130.0.0.0"
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        settings.userAgentString = userAgent

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)

        // Without a window the page would count as hidden; keep its process important and the page running.
        if (Build.VERSION.SDK_INT >= 26) view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        layoutOffscreen()

        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // DRM-protected media is what plays the music: the one thing the page needs to be allowed.
                request.grant(request.resources)
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message()
                if (text.contains("widevine", true) || text.contains("drm", true) || text.contains("EME", false)) {
                    PresenceLog.log("page console: ${text.take(140)}")
                }
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                view?.evaluateJavascript(MUTE_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(MUTE_JS, null)
                loading?.complete(true)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    PresenceLog.log("page error: ${error?.description} (${request.url.host})")
                    loading?.complete(false)
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                PresenceLog.log("the page's process is gone")
                onRenderGone?.invoke()
                return true
            }
        }
        view.onResume()
        view.resumeTimers()
    }

    /** Gives the view a size and a visible state even though it is in no window. */
    private fun layoutOffscreen() {
        runCatching {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 1080, 1920)
            view.dispatchWindowVisibilityChanged(View.VISIBLE)
        }
    }

    /** Signs the page in with the Spotify cookie the app already holds. */
    fun signIn(token: String) {
        if (token.isBlank()) return
        val cookies = CookieManager.getInstance()
        cookies.setCookie("https://open.spotify.com", "sp_dc=$token; Domain=.spotify.com; Path=/; Secure; HttpOnly")
        cookies.flush()
    }

    /** Loads [url]; true once the page has finished loading, false on an error or when [timeoutMs] runs out. */
    suspend fun load(url: String, timeoutMs: Long = 30_000): Boolean {
        if (destroyed) return false
        val waiting = CompletableDeferred<Boolean>()
        loading = waiting
        view.loadUrl(url)
        val ok = withTimeoutOrNull(timeoutMs) { waiting.await() } ?: false
        if (loading === waiting) loading = null
        return ok
    }

    /** Runs a script in the page; its result as JSON text, or null when the page did not answer in time. */
    suspend fun eval(script: String, timeoutMs: Long = 6_000): String? {
        if (destroyed) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                view.evaluateJavascript(script) { raw -> if (cont.isActive) cont.resume(raw ?: "null") }
            }
        }
    }

    /** Runs a script that returns a string, and gives that string (null when there is none). */
    suspend fun evalString(script: String): String? {
        val raw = eval(script) ?: return null
        if (raw == "null" || raw == "undefined") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    suspend fun readState(): WebState? {
        val text = evalString(STATE_JS) ?: return null
        return runCatching {
            val o = JSONObject(text)
            WebState(
                title = o.optString("title"),
                artist = o.optString("artist"),
                sessionState = o.optString("ps"),
                playing = o.optBoolean("playing"),
                seconds = o.optDouble("t", 0.0),
                nowPlaying = o.optString("np"),
                loggedOut = o.optBoolean("login"),
                page = o.optString("page"),
            )
        }.getOrNull()
    }

    /** Keeps every media element of the page muted from now on. */
    suspend fun mute() {
        eval(MUTE_JS)
    }

    /** Lends the view to a window, to look at the page. [detachFrom] gives it back. */
    fun attachTo(container: ViewGroup, host: Context) {
        if (destroyed) return
        (view.parent as? ViewGroup)?.removeView(view)
        wrapper.setBaseContext(host)
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun detachFrom(container: ViewGroup) {
        if (destroyed) return
        container.removeView(view)
        wrapper.setBaseContext(appContext)
        layoutOffscreen()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        loading?.complete(false)
        runCatching {
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    }

    companion object {
        /** Every audio and video element of the page is kept muted, including those it makes later. */
        const val MUTE_JS = """(function(){
if(window.__bcMute){return 'on'}
window.__bcMute=true;
try{
var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'muted');
Object.defineProperty(HTMLMediaElement.prototype,'muted',{configurable:true,get:function(){return d.get.call(this)},set:function(v){d.set.call(this,true)}});
var play=HTMLMediaElement.prototype.play;
HTMLMediaElement.prototype.play=function(){try{d.set.call(this,true)}catch(e){}return play.apply(this,arguments)};
}catch(e){}
setInterval(function(){var m=document.querySelectorAll('audio,video');for(var i=0;i<m.length;i++){if(!m[i].muted){m[i].muted=true}}},300);
return 'on'})()"""

        /** The state of the page's player, as JSON. */
        const val STATE_JS = """(function(){
var ms=navigator.mediaSession,md=ms&&ms.metadata;
var els=Array.prototype.slice.call(document.querySelectorAll('audio,video'));
var playing=false,t=0;
for(var i=0;i<els.length;i++){var m=els[i];if(!m.paused&&!m.ended&&m.readyState>2){playing=true}if(m.currentTime>t){t=m.currentTime}}
var np=document.querySelector('[data-testid="now-playing-widget"]');
return JSON.stringify({title:md?(md.title||''):'',artist:md?(md.artist||''):'',ps:ms?(ms.playbackState||''):'',playing:playing,t:Math.round(t*10)/10,
np:np?(np.getAttribute('aria-label')||''):'',login:!!document.querySelector('[data-testid="login-button"]'),page:location.pathname.slice(0,60)})})()"""

        /** Presses the big play button of a track page. */
        const val CLICK_PLAY_JS = """(function(){
var b=document.querySelector('[data-testid="play-button"]');
if(!b){var all=document.querySelectorAll('button[aria-label]');
for(var i=0;i<all.length;i++){var l=(all[i].getAttribute('aria-label')||'').toLowerCase();
if(l==='play'||l.indexOf('play ')===0||l==='oynat'||l==='çal'||l==='cal'){b=all[i];break}}}
if(!b){return 'none'}
b.click();return 'clicked'})()"""

        /** Presses the player bar's play/pause button, whichever way it is showing. */
        const val TOGGLE_JS = """(function(){
var b=document.querySelector('[data-testid="control-button-playpause"]');
if(!b){return 'none'}
b.click();return 'clicked'})()"""

        /** For when the button did not pause it. */
        const val HARD_PAUSE_JS = """(function(){
var m=document.querySelectorAll('audio,video');
for(var i=0;i<m.length;i++){m[i].pause()}
return 'paused'})()"""

        /** The track rows of a search page, as JSON. */
        const val SCRAPE_JS = """(function(){
var out=[],seen={};
var rows=document.querySelectorAll('[data-testid="tracklist-row"],div[role="row"]');
for(var i=0;i<rows.length;i++){var r=rows[i];
var a=r.querySelector('a[href*="/track/"]');if(!a){continue}
var m=(a.getAttribute('href')||'').match(/\/track\/([A-Za-z0-9]+)/);if(!m||seen[m[1]]){continue}
seen[m[1]]=true;
var artists=[],as=r.querySelectorAll('a[href*="/artist/"]');
for(var j=0;j<as.length;j++){artists.push(as[j].textContent)}
var times=(r.innerText||'').match(/\d{1,2}:\d{2}/g),sec=0;
if(times&&times.length){var p=times[times.length-1].split(':');sec=parseInt(p[0],10)*60+parseInt(p[1],10)}
out.push({id:m[1],title:a.textContent||'',artists:artists,sec:sec})}
return JSON.stringify(out)})()"""

        /** Double-clicks the search row of the given track, which plays it. */
        fun doubleClickJs(id: String) = """(function(){
var rows=document.querySelectorAll('[data-testid="tracklist-row"],div[role="row"]');
for(var i=0;i<rows.length;i++){var a=rows[i].querySelector('a[href*="/track/$id"]');
if(a){rows[i].scrollIntoView();rows[i].dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true,view:window}));return 'clicked'}}
return 'none'})()"""

        /** Asks the page whether Widevine can be used; the answer lands in a variable read by [READ_DRM_JS]. */
        const val DRM_JS = """(function(){
window.__bcDrm='pending';
try{navigator.requestMediaKeySystemAccess('com.widevine.alpha',[{initDataTypes:['cenc'],audioCapabilities:[{contentType:'audio/mp4; codecs="mp4a.40.2"',robustness:'SW_SECURE_CRYPTO'}]}])
.then(function(){window.__bcDrm='ok'}).catch(function(e){window.__bcDrm='failed: '+e})}catch(e){window.__bcDrm='failed: '+e}
return 'asked'})()"""

        const val READ_DRM_JS = "String(window.__bcDrm)"
    }
}
