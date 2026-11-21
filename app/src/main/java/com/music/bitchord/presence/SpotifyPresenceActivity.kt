package com.music.bitchord.presence

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What the Spotify activity is doing, in plain text: its state, the steps it
 * took and what the root commands answered. Also the way out when the Spotify
 * app was left muted — see [RootSpotify].
 */
class SpotifyPresenceActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView

    private fun refresh() {
        val s = SpotifyPresence.status.value
        status.text = "${s.state}  ${s.detail}".trim()
        log.text = SpotifyPresence.log().ifBlank { "(empty)" }
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
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setAllCaps(false)
            setOnClickListener { action() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 0)
            addView(button("Refresh") { refresh() })
            addView(
                button("Copy") {
                    val clipboard = this@SpotifyPresenceActivity
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("presence log", log.text))
                },
            )
            addView(
                button("Unmute Spotify") {
                    SpotifyPresence.restoreSound()
                    log.text = "Spotify's sound is being put back…\n\n" + log.text
                },
            )
            addView(button("Close") { finish() })
        }
        val bar2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 16, 8)
            addView(
                button("Self-test") {
                    SpotifyPresence.selfTest()
                    log.postDelayed({ refresh() }, 6_000)
                    log.text = "Testing what this phone allows; the report appears in a few seconds…\n\n" + log.text
                },
            )
            val flash = button("") {}
            fun flashLabel() { flash.text = if (SpotifyPresence.allowFlash) "Flash: allowed" else "Flash: never" }
            flash.setOnClickListener {
                SpotifyPresence.allowFlash = !SpotifyPresence.allowFlash
                flashLabel()
            }
            flashLabel()
            addView(flash)
        }
        val scroll = ScrollView(this).apply { addView(log) }
        root.addView(status)
        root.addView(bar)
        root.addView(bar2)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    companion object {
        const val EXTRA_SPDC = "spdc"
    }
}
