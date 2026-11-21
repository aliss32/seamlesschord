package com.music.bitchord.presence

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What the Spotify activity is doing, in plain text: its state and the steps
 * it took. It can also show the web page that does the playing (to sign in,
 * or to see what Spotify says) and run a check of what this phone allows.
 */
class SpotifyPresenceActivity : Activity() {

    companion object {
        const val EXTRA_SPDC = "spdc"
        private const val REFRESH_MS = 1_200L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var playerBox: FrameLayout
    private lateinit var playerButton: Button
    private var playerShown = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private fun refresh() {
        val s = SpotifyPresence.status.value
        status.text = "${s.state}  ${s.detail}".trim()
        val text = SpotifyPresence.log().ifBlank { "(nothing yet)" }
        if (text == log.text.toString()) return
        val atBottom = scroll.scrollY + scroll.height >= log.bottom - 48
        log.text = text
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setPlayerShown(show: Boolean) {
        if (show) {
            if (!SpotifyPresence.showPlayer(this, playerBox)) {
                refresh()
                return
            }
            playerBox.visibility = View.VISIBLE
        } else {
            SpotifyPresence.hidePlayer(playerBox)
            playerBox.visibility = View.GONE
        }
        playerShown = show
        playerButton.text = if (show) "Hide web player" else "Show web player"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            fitsSystemWindows = true
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 24, 24, 8)
        }
        log = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 8, 24, 24)
        }
        scroll = ScrollView(this).apply { addView(log) }
        playerBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 12f
            setOnClickListener { action() }
        }
        playerButton = button("Show web player") { setPlayerShown(!playerShown) }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 16, 0)
            addView(
                button("Copy log") {
                    val clipboard = this@SpotifyPresenceActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("presence log", log.text))
                },
            )
            addView(button("Clear") { SpotifyPresence.clearLog(); refresh() })
            addView(button("Restart track") { SpotifyPresence.restart(); refresh() })
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 16, 8)
            addView(button("Self-test") { SpotifyPresence.selfTest() })
            addView(playerButton)
            addView(button("Close") { finish() })
        }
        root.addView(status)
        root.addView(row1)
        root.addView(row2)
        root.addView(playerBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        // The page goes back to doing its job without a window.
        if (playerShown) setPlayerShown(false)
        super.onStop()
    }
}
