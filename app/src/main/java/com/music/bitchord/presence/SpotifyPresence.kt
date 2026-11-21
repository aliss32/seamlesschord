package com.music.bitchord.presence

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Lets Spotify see what is playing in BitChord, by playing the same track,
 * muted, in Spotify's own web player — which is what puts it in Spotify's
 * activity — for the listener who has asked for that and is signed in.
 *
 * This side is the app's; the page itself runs in a process of its own (see
 * [PresenceWebHost]). It only tells that process which track is playing, and
 * only once the track has held for a few seconds, so skipping through a queue
 * does not become a burst of searches. It is a no-op — one setting read per
 * progress tick — while the setting is off.
 *
 * Only tracks Spotify actually has can be shown. For one it does not, the page
 * is stopped and the status says so, rather than leaving the previous track up.
 */
object SpotifyPresence {

    data class Status(val state: PresenceState = PresenceState.OFF, val detail: String = "")

    /** What the presence process last reported; [PresenceState.OFF] whenever it is not running. */
    val status = MutableStateFlow(Status())

    private data class Track(val id: String, val title: String, val artist: String, val durationSec: Int?)

    private const val DEBOUNCE_MS = 4_000L
    private const val IDLE_SHUTDOWN_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var service: Messenger? = null
    private var bound = false
    private var latest: Track? = null
    private var latestPlaying = false
    private var sentSpdc: String? = null
    private var debounce: Job? = null
    private var idleStop: Job? = null

    private val incoming = Messenger(
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != PresenceProtocol.MSG_STATUS) return
                val state = runCatching {
                    PresenceState.valueOf(msg.data.getString(PresenceProtocol.KEY_STATE).orEmpty())
                }.getOrNull() ?: return
                status.value = Status(state, msg.data.getString(PresenceProtocol.KEY_DETAIL).orEmpty())
            }
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            // A fresh process knows nothing: hand it the session and the track again.
            sentSpdc = null
            if (latestPlaying) push()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            sentSpdc = null
            status.value = Status(PresenceState.ERROR, "the presence process stopped")
        }
    }

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        scope.launch {
            combine(AppSettings.spotifyPresenceEnabled, AppSettings.spotifySpdcToken) { on, token ->
                on && token.isNotBlank()
            }.distinctUntilChanged().collect { on ->
                if (!on) {
                    shutdown()
                } else if (latest != null && latestPlaying) {
                    debounce?.cancel()
                    debounce = scope.launch { push() }
                }
            }
        }
    }

    private fun enabled() =
        AppSettings.spotifyPresenceEnabled.value && AppSettings.spotifySpdcToken.value.isNotBlank()

    /** Called from the player: on each progress tick while playing, and when playback pauses. */
    fun onPlayback(mediaId: String?, title: String?, artist: String?, durationMs: Long, playing: Boolean) {
        if (!enabled()) return
        val id = mediaId ?: return
        val name = title?.takeIf { it.isNotBlank() } ?: return
        val track = Track(id, name, artist.orEmpty(), (durationMs / 1000).toInt().takeIf { it > 0 })
        if (track == latest && playing == latestPlaying) return
        latest = track
        latestPlaying = playing
        debounce?.cancel()
        // A pause is reported at once; a new track only once it has held.
        debounce = scope.launch {
            if (playing) delay(DEBOUNCE_MS)
            push()
        }
    }

    private fun push() {
        val track = latest ?: return
        if (!enabled()) {
            shutdown()
            return
        }
        idleStop?.cancel()
        if (!latestPlaying) {
            if (bound) send(PresenceProtocol.MSG_PAUSE, Bundle())
            // A pause that lasts lets the process go entirely.
            idleStop = scope.launch {
                delay(IDLE_SHUTDOWN_MS)
                shutdown()
            }
            return
        }
        ensureBound()
        val token = AppSettings.spotifySpdcToken.value
        if (sentSpdc != token) {
            send(PresenceProtocol.MSG_CONFIGURE, Bundle().apply { putString(PresenceProtocol.KEY_SPDC, token) })
            sentSpdc = token
        }
        send(
            PresenceProtocol.MSG_TRACK,
            Bundle().apply {
                putString(PresenceProtocol.KEY_TITLE, track.title)
                putString(PresenceProtocol.KEY_ARTIST, track.artist)
                putInt(PresenceProtocol.KEY_DURATION_SEC, track.durationSec ?: -1)
            },
        )
    }

    private fun ensureBound() {
        val context = app ?: return
        if (bound) return
        bound = context.bindService(
            Intent(context, SpotifyPresenceService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (bound) status.value = Status(PresenceState.STARTING)
    }

    private fun send(what: Int, data: Bundle) {
        // Not connected yet: the connection callback sends everything again.
        val target = service ?: return
        val message = Message.obtain(null, what).apply {
            this.data = data
            replyTo = incoming
        }
        runCatching { target.send(message) }
    }

    /** Ends the session and lets go of the process. */
    private fun shutdown() {
        idleStop?.cancel()
        debounce?.cancel()
        if (bound) {
            runCatching { app?.unbindService(connection) }
            bound = false
        }
        service = null
        sentSpdc = null
        status.value = Status()
    }
}
