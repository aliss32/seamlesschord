package com.music.bitchord.presence

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Spotify's own web player, run silently in this process so that Spotify
 * itself sees the track being played and shows it as the listener's activity.
 *
 * Lives in the `:spotify` process, never the app's main one: a WebView that
 * crashes, leaks or is killed under memory pressure takes only this process
 * with it, and the player the listener is actually hearing is untouched.
 *
 * Everything here runs on this process's main thread.
 *
 * It is driven the way a person would drive the page — search for the
 * track, play the matching row — because there is no API for it. The page
 * markup is Spotify's to change; the selectors are all in the scripts below,
 * and the log kept by [recentLog] (shown in [SpotifyPresenceActivity]) says
 * which step stopped when one of them stops matching.
 */
internal object PresenceWebHost {

    const val HOME = "https://open.spotify.com/"

    private const val POLL_MS = 2_000L
    private const val KEEPALIVE_MS = 15_000L
    private const val MAX_POLLS = 10
    private const val MAX_ATTEMPTS = 2
    private const val LOG_LINES = 80
    private const val DURATION_TOLERANCE_SEC = 8

    private enum class Phase { IDLE, SEARCHING, STARTING, PLAYING, PAUSED }

    private class Want(val title: String, val artist: String, val durationSec: Int?)

    private val ui = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var contextWrapper: MutableContextWrapper? = null
    private var webView: WebView? = null
    private var attachedTo: ViewGroup? = null

    private var spdc = ""
    private var want: Want? = null
    private var phase = Phase.IDLE
    private var attempt = 0
    private var polls = 0

    /** Whether the service that drives this is alive; the viewer alone never keeps the page running. */
    var serviceActive = false

    /** Silent unless the listener turns it on in the viewer, and silent again whenever the viewer closes. */
    var muted = true
        private set

    var state: PresenceState = PresenceState.OFF
        private set
    var detail: String = ""
        private set

    private val observers = LinkedHashSet<(PresenceState, String) -> Unit>()
    private val logLines = ArrayDeque<String>()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // A second process must not share the main process's WebView data
        // directory — Android refuses outright, and would otherwise take the
        // sign-in cookies of one with it.
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { WebView.setDataDirectorySuffix("presence") }
        }
    }

    fun addObserver(observer: (PresenceState, String) -> Unit) {
        observers += observer
    }

    fun removeObserver(observer: (PresenceState, String) -> Unit) {
        observers -= observer
    }

    fun recentLog(): String = logLines.joinToString("\n")

    fun webViewOrNull(): WebView? = webView

    /** Lower this process's main thread so the page never competes with the player for a core. */
    fun setLowPriority(low: Boolean) {
        runCatching {
            Process.setThreadPriority(
                if (low) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT,
            )
        }
    }

    // ── Session ─────────────────────────────────────────────────────────────

    /** Hands the page the listener's Spotify session, the same cookie the library sign-in captured. */
    fun configure(newSpdc: String) {
        if (newSpdc.isBlank() || newSpdc == spdc) return
        spdc = newSpdc
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setCookie(HOME, "sp_dc=$newSpdc; Domain=.spotify.com; Path=/; Secure; HttpOnly")
        cookies.flush()
    }

    fun applyMuted(value: Boolean) {
        muted = value
        webView?.evaluateJavascript(stateScript(), null)
    }

    // ── What is asked of it ─────────────────────────────────────────────────

    fun play(title: String, artist: String, durationSec: Int?) {
        val previous = want
        val same = previous != null && previous.title == title && previous.artist == artist
        want = Want(title, artist, durationSec)
        if (same) {
            when (phase) {
                Phase.PAUSED -> {
                    resume()
                    return
                }
                Phase.SEARCHING, Phase.STARTING, Phase.PLAYING -> return
                Phase.IDLE -> Unit
            }
        }
        attempt = 0
        startSearch()
    }

    fun pause() {
        stopTimers()
        if (phase == Phase.PLAYING || phase == Phase.STARTING) {
            webView?.evaluateJavascript(PAUSE_SCRIPT, null)
            phase = Phase.PAUSED
            publish(PresenceState.PAUSED)
        }
    }

    /** Ends the session. The page is torn down unless the viewer is looking at it. */
    fun stop() {
        stopTimers()
        want = null
        phase = Phase.IDLE
        if (attachedTo == null) {
            teardown()
        } else {
            webView?.evaluateJavascript(PAUSE_SCRIPT, null)
        }
        publish(PresenceState.OFF)
    }

    private fun resume() {
        phase = Phase.STARTING
        polls = 0
        publish(PresenceState.STARTING, "resuming")
        webView?.evaluateJavascript(RESUME_SCRIPT, null)
        schedulePoll(POLL_MS)
    }

    private fun startSearch() {
        val w = want ?: return
        val wv = ensureWebView() ?: return
        stopTimers()
        phase = Phase.SEARCHING
        publish(PresenceState.SEARCHING, "${w.title} — ${w.artist}")
        wv.loadUrl(HOME + "search/" + Uri.encode("${w.title} ${w.artist}".trim()) + "/tracks")
    }

    // ── The page ────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        val context = appContext ?: return null
        val wrapper = MutableContextWrapper(context)
        val wv = WebView(wrapper)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // The cover art is the heaviest thing on the page and nothing
            // here reads it; it is only fetched while the viewer is open.
            blockNetworkImage = attachedTo == null
            // The web player will not play on the mobile site, which sends
            // the listener to the app instead.
            userAgentString = desktopUserAgent(userAgentString)
        }
        wv.setBackgroundColor(0xFF121212.toInt())
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.addJavascriptInterface(JsBridge(), "PresenceBridge")
        wv.webViewClient = client
        wv.webChromeClient = chrome
        if (Build.VERSION.SDK_INT >= 26) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        }
        wv.onResume()
        contextWrapper = wrapper
        webView = wv
        return wv
    }

    private fun teardown() {
        stopTimers()
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        runCatching {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.removeJavascriptInterface("PresenceBridge")
            wv.destroy()
        }
        webView = null
        contextWrapper = null
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isAllowedHost(request.url)

        override fun onPageFinished(view: WebView, url: String?) {
            val address = url.orEmpty()
            if (phase != Phase.IDLE && address.contains("accounts.spotify.com")) {
                phase = Phase.IDLE
                publish(PresenceState.SIGN_IN_NEEDED, "Spotify asked to sign in")
                return
            }
            if (phase == Phase.SEARCHING && address.contains("/search/")) {
                view.evaluateJavascript(SCRAPE_SCRIPT, null)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            teardown()
            phase = Phase.IDLE
            publish(PresenceState.ERROR, "the web page's renderer stopped")
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        /** The web player's audio is DRM-protected; without this it refuses to start. */
        override fun onPermissionRequest(request: PermissionRequest) {
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            log("js: ${message.message()}")
            return true
        }
    }

    // ── The viewer ──────────────────────────────────────────────────────────

    fun attachTo(activity: Activity, container: ViewGroup) {
        val wv = ensureWebView() ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        contextWrapper?.setBaseContext(activity)
        attachedTo = container
        wv.settings.blockNetworkImage = false
        container.addView(
            wv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setLowPriority(false)
        if (wv.url == null) wv.loadUrl(HOME)
    }

    fun detach() {
        val wv = webView
        (wv?.parent as? ViewGroup)?.removeView(wv)
        appContext?.let { contextWrapper?.setBaseContext(it) }
        attachedTo = null
        wv?.settings?.blockNetworkImage = true
        // Back to silent, whatever the viewer left it at.
        muted = true
        wv?.evaluateJavascript(stateScript(), null)
        setLowPriority(serviceActive)
        if (!serviceActive) teardown()
    }

    // ── Steps, driven by what the page reports ──────────────────────────────

    private fun handleRows(json: String) {
        val w = want ?: return
        if (phase != Phase.SEARCHING) return
        val result = runCatching { JSONObject(json) }.getOrNull()
        if (result == null) {
            fail("the search page could not be read")
            return
        }
        if (result.optBoolean("login")) {
            phase = Phase.IDLE
            publish(PresenceState.SIGN_IN_NEEDED, "Spotify is not signed in")
            return
        }
        val rows = result.optJSONArray("rows") ?: JSONArray()
        val pick = bestRow(w, rows)
        if (pick == null) {
            phase = Phase.IDLE
            // Whatever Spotify was last playing must not stay up as if it were this.
            webView?.evaluateJavascript(PAUSE_SCRIPT, null)
            publish(PresenceState.NOT_FOUND, "no matching track among ${rows.length()} results")
            return
        }
        phase = Phase.STARTING
        polls = 0
        publish(PresenceState.STARTING, "starting result ${pick + 1}")
        webView?.evaluateJavascript(playScript(pick), null)
        schedulePoll(POLL_MS)
    }

    private fun handleState(json: String) {
        val w = want ?: return
        val result = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (result.optBoolean("login")) {
            phase = Phase.IDLE
            stopTimers()
            publish(PresenceState.SIGN_IN_NEEDED, "Spotify is not signed in")
            return
        }
        val onTrack = result.optBoolean("playing") &&
            normalize(result.optString("title")).contains(normalize(w.title))
        when (phase) {
            Phase.STARTING -> when {
                onTrack -> {
                    phase = Phase.PLAYING
                    publish(PresenceState.PLAYING, if (muted) "muted" else "audible")
                    schedulePoll(KEEPALIVE_MS)
                }
                ++polls >= MAX_POLLS -> retryOrFail("it never started playing")
                else -> schedulePoll(POLL_MS)
            }
            Phase.PLAYING -> if (onTrack) {
                schedulePoll(KEEPALIVE_MS)
            } else {
                retryOrFail("Spotify moved to another track")
            }
            else -> Unit
        }
    }

    private fun retryOrFail(why: String) {
        if (++attempt < MAX_ATTEMPTS && want != null) {
            log("retrying: $why")
            startSearch()
        } else {
            fail(why)
        }
    }

    private fun fail(why: String) {
        stopTimers()
        phase = Phase.IDLE
        webView?.evaluateJavascript(PAUSE_SCRIPT, null)
        publish(PresenceState.ERROR, why)
    }

    private fun schedulePoll(delayMs: Long) {
        ui.removeCallbacks(poll)
        ui.postDelayed(poll, delayMs)
    }

    private val poll = Runnable { webView?.evaluateJavascript(stateScript(), null) }

    private fun stopTimers() {
        ui.removeCallbacks(poll)
    }

    // ── Matching the Spotify result to the track ────────────────────────────

    private fun bestRow(w: Want, rows: JSONArray): Int? {
        val wantTitle = normalize(w.title)
        val wantArtists = artistsOf(w.artist)
        var best: Int? = null
        var bestScore = Int.MIN_VALUE
        for (k in 0 until rows.length()) {
            val row = rows.optJSONObject(k) ?: continue
            val title = normalize(row.optString("title"))
            if (title.isBlank() || wantTitle.isBlank()) continue
            val titleScore = when {
                title == wantTitle -> 3
                title.contains(wantTitle) || wantTitle.contains(title) -> 1
                else -> 0
            }
            if (titleScore == 0) continue
            val got = row.optJSONArray("artists")
            val gotArtists = (0 until (got?.length() ?: 0)).map { normalize(got?.optString(it)) }.filter { it.isNotBlank() }
            val artistOk = wantArtists.isEmpty() || gotArtists.isEmpty() ||
                wantArtists.any { a -> gotArtists.any { g -> g == a || g.contains(a) || a.contains(g) } }
            if (!artistOk) continue
            var score = titleScore * 100
            val seconds = row.optInt("dur", -1)
            val wanted = w.durationSec
            if (seconds >= 0 && wanted != null) {
                val gap = abs(seconds - wanted)
                if (gap > DURATION_TOLERANCE_SEC) continue
                score -= gap
            }
            if (score > bestScore) {
                bestScore = score
                best = row.optInt("i", k)
            }
        }
        return best
    }

    private fun artistsOf(credit: String): List<String> =
        credit.lowercase()
            .split(Regex("""\s*(?:,|;|/|&|\bfeat\.?|\bft\.?|\bx\b|\band\b|\bve\b)\s*"""))
            .map { normalize(it) }
            .filter { it.isNotBlank() }

    private fun normalize(text: String?): String =
        text.orEmpty().lowercase()
            .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun isAllowedHost(uri: Uri?): Boolean {
        if (uri?.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun desktopUserAgent(platform: String?): String {
        val chrome = Regex("""Chrome/([\d.]+)""").find(platform.orEmpty())?.groupValues?.get(1) ?: "124.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    private fun publish(newState: PresenceState, newDetail: String = "") {
        state = newState
        detail = newDetail
        log("$newState $newDetail".trim())
        observers.toList().forEach { it(newState, newDetail) }
    }

    private fun log(line: String) {
        logLines.addLast(line)
        while (logLines.size > LOG_LINES) logLines.removeFirst()
    }

    /** Called from the page's thread; hops to this process's main one. */
    internal class JsBridge {
        @JavascriptInterface
        fun onRows(json: String) {
            ui.post { handleRows(json) }
        }

        @JavascriptInterface
        fun onState(json: String) {
            ui.post { handleState(json) }
        }
    }

    // ── What is run inside the page ─────────────────────────────────────────

    private val ALLOWED_HOSTS = listOf(
        "spotify.com", "scdn.co", "spotifycdn.com", "google.com", "gstatic.com", "facebook.com", "apple.com",
    )

    private fun playScript(index: Int): String = """
        (function (i) {
            var rows = window.__bcRows || [];
            var row = rows[i];
            if (!row) { console.log('play: no row ' + i); return; }
            row.scrollIntoView({ block: 'center' });
            row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));
        })($index);
    """.trimIndent()

    private fun stateScript(): String = """
        (function () {
            var media = [].slice.call(document.querySelectorAll('audio,video'));
            media.forEach(function (m) { try { m.muted = $muted; } catch (e) {} });
            var playing = media.some(function (m) { return !m.paused && !m.ended; });
            PresenceBridge.onState(JSON.stringify({
                playing: playing,
                title: document.title || '',
                login: !!document.querySelector('[data-testid="login-button"]')
            }));
        })();
    """.trimIndent()

    private val SCRAPE_SCRIPT = """
        (function () {
            var tries = 0;
            function grab() {
                var rows = [].slice.call(document.querySelectorAll('[data-testid="tracklist-row"]'));
                if (!rows.length) {
                    rows = [].slice.call(document.querySelectorAll('div[role="row"]')).filter(function (r) {
                        return r.querySelector('a[href*="/track/"]');
                    });
                }
                if (rows.length) {
                    window.__bcRows = rows;
                    var out = rows.slice(0, 10).map(function (r, i) {
                        var t = r.querySelector('a[href*="/track/"]');
                        var artists = [].slice.call(r.querySelectorAll('a[href*="/artist/"]')).map(function (a) { return a.textContent; });
                        var m = (r.textContent || '').trim().match(/(\d{1,2}):(\d{2})$/);
                        return { i: i, title: t ? t.textContent : '', artists: artists, dur: m ? parseInt(m[1], 10) * 60 + parseInt(m[2], 10) : null };
                    });
                    PresenceBridge.onRows(JSON.stringify({ login: false, rows: out }));
                    return;
                }
                if (++tries < 40) { setTimeout(grab, 250); return; }
                PresenceBridge.onRows(JSON.stringify({ login: !!document.querySelector('[data-testid="login-button"]'), rows: [] }));
            }
            grab();
        })();
    """.trimIndent()

    private val PAUSE_SCRIPT = """
        (function () {
            var media = [].slice.call(document.querySelectorAll('audio,video'));
            var button = document.querySelector('[data-testid="control-button-playpause"]');
            if (media.some(function (m) { return !m.paused; })) {
                if (button) { button.click(); } else { media.forEach(function (m) { m.pause(); }); }
            }
        })();
    """.trimIndent()

    private val RESUME_SCRIPT = """
        (function () {
            var media = [].slice.call(document.querySelectorAll('audio,video'));
            var button = document.querySelector('[data-testid="control-button-playpause"]');
            if (!media.some(function (m) { return !m.paused; }) && button) { button.click(); }
        })();
    """.trimIndent()
}
