package com.music.bitchord.presence

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.media.MediaDrm
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.music.bitchord.data.settings.AppSettings
import org.json.JSONTokener
import java.util.UUID

/**
 * A test bench, not the feature: can this phone's own WebView play the Spotify
 * web player? It loads open.spotify.com as desktop Chrome (the mobile page
 * does not offer a player), signs in with the app's Spotify cookie, lets the
 * page use DRM-protected media, and reports what happens in plain text.
 */
class SpotifyWebProbeActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private val lines = ArrayDeque<String>()

    private fun say(line: String) {
        runOnUiThread {
            lines.addLast(line)
            while (lines.size > 80) lines.removeFirst()
            out.text = lines.joinToString("\n")
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    /** What the page's scripts tell us. */
    inner class Probe {
        @JavascriptInterface
        fun report(text: String) {
            say(text)
        }
    }

    private fun js(script: String) {
        web.evaluateJavascript(script, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            fitsSystemWindows = true
        }
        web = WebView(this)
        out = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(16, 8, 16, 8)
        }
        scroll = ScrollView(this).apply { addView(out) }

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 12f
            setOnClickListener { action() }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("DRM") { drmTest() })
            addView(button("Open Spotify") { web.loadUrl("https://open.spotify.com/") })
            addView(button("Mute") { mute() })
            addView(button("Status") { status() })
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                button("Play/Pause") {
                    js("(function(){var b=document.querySelector('[data-testid=\"control-button-playpause\"]');if(b){b.click();Probe.report('clicked play/pause')}else{Probe.report('no play/pause button on this page')}})()")
                },
            )
            addView(
                button("Copy") {
                    val clipboard = this@SpotifyWebProbeActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("web probe", out.text))
                },
            )
            addView(button("Close") { finish() })
        }
        root.addView(row1)
        root.addView(row2)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360))
        setContentView(root)

        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        val chrome = Regex("""Chrome/([\d.]+)""").find(settings.userAgentString)?.groupValues?.get(1) ?: "120.0.0.0"
        settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        say("webview user agent: desktop Chrome/$chrome")

        web.addJavascriptInterface(Probe(), "Probe")
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    say("page asked for: " + request.resources.joinToString { it.substringAfterLast('.') })
                    request.grant(request.resources)
                }
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message()
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                    text.contains("drm", true) || text.contains("widevine", true) || text.contains("eme", true)
                ) {
                    say("console: ${text.take(160)}")
                }
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                say("loaded: ${url?.take(80)}")
            }
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        val token = AppSettings.spotifySpdcToken.value
        if (token.isNotBlank()) {
            cookies.setCookie("https://open.spotify.com", "sp_dc=$token; Domain=.spotify.com; Path=/; Secure; HttpOnly")
            cookies.flush()
            say("signed in with the app's Spotify cookie")
        } else {
            say("no Spotify cookie in the app: sign in inside the page")
        }
        drmTest()
        web.loadUrl("https://open.spotify.com/")
    }

    private fun drmTest() {
        @Suppress("DEPRECATION")
        val platform = runCatching {
            val drm = MediaDrm(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
            val text = "vendor=${drm.getPropertyString("vendor")}, version=${drm.getPropertyString("version")}, " +
                "level=${drm.getPropertyString("securityLevel")}"
            drm.release()
            text
        }.getOrElse { "MediaDrm failed: ${it.message}" }
        say("platform widevine: $platform")
        js(
            "navigator.requestMediaKeySystemAccess('com.widevine.alpha',[{initDataTypes:['cenc']," +
                "audioCapabilities:[{contentType:'audio/mp4; codecs=\"mp4a.40.2\"',robustness:'SW_SECURE_CRYPTO'}]}])" +
                ".then(function(a){Probe.report('page widevine: ok')})" +
                ".catch(function(e){Probe.report('page widevine: failed '+e)})",
        )
    }

    /** Keeps every audio and video element of the page muted; the page may create new ones at any time. */
    private fun mute() {
        js(
            "if(!window.__muter){window.__muter=setInterval(function(){document.querySelectorAll('audio,video')" +
                ".forEach(function(m){m.muted=true})},400)}Probe.report('muting every media element')",
        )
    }

    private fun status() {
        web.evaluateJavascript(
            "JSON.stringify({title:document.title,pp:!!document.querySelector('[data-testid=\"control-button-playpause\"]')," +
                "login:!!document.querySelector('[data-testid=\"login-button\"]')," +
                "media:Array.prototype.map.call(document.querySelectorAll('audio,video'),function(m){" +
                "return {paused:m.paused,t:Math.round(m.currentTime),muted:m.muted,src:(m.currentSrc||'').slice(0,30)}})})",
        ) { raw ->
            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
            say("status: $text")
        }
    }

    override fun onPause() {
        // Left running on purpose: the question is whether it keeps playing in the background.
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
