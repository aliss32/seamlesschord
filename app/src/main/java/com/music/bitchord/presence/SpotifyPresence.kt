package com.music.bitchord.presence

import android.content.Context
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import java.io.File
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets Spotify see what is playing in BitChord, by having the Spotify app play
 * the same track, muted and out of sight (see [RootSpotify], which needs root).
 *
 * It only acts once a track has held for a few seconds, so skipping through a
 * queue does not become a burst of searches and launches. It is a no-op — one
 * setting read per progress tick — while the setting is off.
 *
 * Only tracks Spotify actually has can be shown. For one it does not, Spotify
 * is stopped and the status says so, rather than leaving the previous track up.
 */
object SpotifyPresence {

    data class Status(val state: PresenceState = PresenceState.OFF, val detail: String = "")

    val status = MutableStateFlow(Status())

    /**
     * True while the muted Spotify app is up. It takes audio focus (it does not
     * play without it), and the player must not give way to that.
     */
    val ignoreFocus = MutableStateFlow(false)

    private data class Track(val id: String, val title: String, val artist: String, val durationSec: Int?)

    private const val DEBOUNCE_MS = 4_000L
    private const val STOP_AFTER_PAUSE_MS = 45_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var latest: Track? = null
    private var latestPlaying = false
    private var debounce: Job? = null
    private var idleStop: Job? = null
    private var work: Job? = null
    private var spotifyUp = false
    private var startedFor: String? = null
    private var rootChecked = false
    private var beat: Job? = null

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        RootSpotify.ownPackage = context.packageName
        RootSpotify.beatFile = File(context.filesDir, "presence.beat").absolutePath
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

    fun log(): String = RootSpotify.recentLog()

    /** For when Spotify was left muted (the app was killed mid-session): puts its sound back. */
    fun restoreSound() {
        scope.launch(Dispatchers.IO) { RootSpotify.restoreSound() }
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
            if (spotifyUp) status.value = Status(PresenceState.PAUSED)
            // A pause that lasts ends the Spotify session altogether.
            idleStop = scope.launch {
                delay(STOP_AFTER_PAUSE_MS)
                shutdown()
            }
            return
        }
        if (spotifyUp && startedFor == track.id) {
            status.value = Status(PresenceState.PLAYING, "muted, hidden screen")
            return
        }
        work?.cancel()
        work = scope.launch { start(track) }
    }

    private suspend fun start(track: Track) {
        if (!rootChecked) {
            status.value = Status(PresenceState.STARTING, "checking root")
            val problem = withContext(Dispatchers.IO) { RootSpotify.check() }
            if (problem != null) {
                status.value = Status(PresenceState.ERROR, problem)
                return
            }
            rootChecked = true
        }
        status.value = Status(PresenceState.SEARCHING, "${track.title} — ${track.artist}")
        val lookup = RootSpotify.findTrack(track.title, track.artist, track.durationSec)
        // The search API failing is not the end: the Spotify app can search for it itself.
        val fallback = lookup.limited || (lookup.error != null)
        if (lookup.id == null && !fallback) {
            // Whatever Spotify was last playing must not stay up as if it were this.
            if (spotifyUp) withContext(Dispatchers.IO) { RootSpotify.stop() }
            stopKeepAlive()
            spotifyUp = false
            startedFor = null
            ignoreFocus.value = false
            status.value = Status(PresenceState.NOT_FOUND)
            return
        }
        status.value = Status(PresenceState.STARTING, "opening it in the Spotify app")
        ignoreFocus.value = true
        withContext(Dispatchers.IO) { startKeepAlive() }
        val screen = app?.let { withContext(Dispatchers.IO) { VirtualScreen.open(it) } }
        val problem = withContext(Dispatchers.IO) {
            val id = lookup.id
            fun open(display: Int?) = if (id != null) {
                RootSpotify.play(id, display)
            } else {
                RootSpotify.playFromSearch(track.title, track.artist, display)
            }
            var result = open(screen)
            if (result != null && screen != null) {
                // The system would not put Spotify on the hidden screen: a moment on the real one instead.
                RootSpotify.log("hidden screen refused (${result.take(80)}); trying the real screen")
                result = open(null)
            }
            result
        }
        if (problem != null) {
            // Not playing: nothing is left half-started, muted or holding the player's focus.
            withContext(Dispatchers.IO) { RootSpotify.stop() }
            stopKeepAlive()
            spotifyUp = false
            startedFor = null
            ignoreFocus.value = false
            status.value = Status(PresenceState.ERROR, problem)
            return
        }
        spotifyUp = true
        startedFor = track.id
        status.value = Status(PresenceState.PLAYING, "muted, hidden screen")
    }

    /** The heartbeat the root watchdog reads, and the watchdog itself. */
    private suspend fun startKeepAlive() {
        val file = RootSpotify.beatFile ?: return
        runCatching { File(file).writeText(System.currentTimeMillis().toString()) }
        RootSpotify.startWatchdog()
        if (beat?.isActive == true) return
        beat = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { File(file).writeText(System.currentTimeMillis().toString()) }
                delay(5_000)
            }
        }
    }

    private fun stopKeepAlive() {
        beat?.cancel()
        beat = null
    }

    /**
     * The player is going away (or the app was swiped off): Spotify, its hidden
     * screen and its audio settings go too. The root command runs on a thread of
     * its own, which outlives this call and the process.
     */
    fun endSession() {
        idleStop?.cancel()
        debounce?.cancel()
        work?.cancel()
        val wasUp = spotifyUp
        spotifyUp = false
        startedFor = null
        rootChecked = false
        status.value = Status()
        stopKeepAlive()
        ignoreFocus.value = false
        VirtualScreen.close()
        if (wasUp) Thread { RootSpotify.stop() }.start()
    }

    /** Ends the session: Spotify is stopped and its audio settings are put back. */
    private fun shutdown() {
        idleStop?.cancel()
        debounce?.cancel()
        work?.cancel()
        val wasUp = spotifyUp
        spotifyUp = false
        startedFor = null
        rootChecked = false
        status.value = Status()
        stopKeepAlive()
        ignoreFocus.value = false
        VirtualScreen.close()
        if (wasUp) scope.launch(Dispatchers.IO) { RootSpotify.stop() }
    }
}
