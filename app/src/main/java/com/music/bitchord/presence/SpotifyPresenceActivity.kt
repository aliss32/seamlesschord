package com.music.bitchord.presence

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * A window onto the Spotify web player the app runs in the background — to
 * sign in again when Spotify asks, see what it is doing, and check that the
 * markup the driver depends on has not changed. Runs in the same process as
 * the player so it shows the very page, not a copy.
 *
 * The page is muted whenever this is not open: the switch here is for this
 * visit only, and closing the window puts it back.
 */
class SpotifyPresenceActivity : Activity() {

    private lateinit var status: TextView

    private val observer: (PresenceState, String) -> Unit = { state, detail ->
        runOnUiThread { status.text = "$state  $detail".trim() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PresenceWebHost.init(applicationContext)
        intent.getStringExtra(EXTRA_SPDC)?.takeIf { it.isNotBlank() }?.let(PresenceWebHost::configure)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            fitsSystemWindows = true
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mute = Switch(this).apply {
            text = "Muted "
            isChecked = PresenceWebHost.muted
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> PresenceWebHost.applyMuted(checked) }
        }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        val log = Button(this).apply {
            text = "Log"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SpotifyPresenceActivity)
                    .setTitle("Presence log")
                    .setMessage(PresenceWebHost.recentLog().ifBlank { "(empty)" })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 8, 24, 8)
            addView(status)
            addView(mute)
            addView(log)
            addView(close)
        }
        val container = FrameLayout(this)
        root.addView(bar)
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        PresenceWebHost.attachTo(this, container)
        PresenceWebHost.addObserver(observer)
        observer(PresenceWebHost.state, PresenceWebHost.detail)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val page = PresenceWebHost.webViewOrNull()
        if (page != null && page.canGoBack()) page.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        PresenceWebHost.removeObserver(observer)
        PresenceWebHost.detach()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SPDC = "spdc"
    }
}
