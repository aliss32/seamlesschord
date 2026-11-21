package com.music.bitchord.presence

import android.content.Context
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
            status.value = Status(PresenceState.PLAYING, "muted, in the Spotify app")
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
        if (lookup.error != null) {
            status.value = Status(
                if (lookup.error.contains("sign-in")) PresenceState.SIGN_IN_NEEDED else PresenceState.ERROR,
                lookup.error,
            )
            return
        }
        val id = lookup.id
        if (id == null) {
            // Whatever Spotify was last playing must not stay up as if it were this.
            if (spotifyUp) withContext(Dispatchers.IO) { RootSpotify.stop() }
            spotifyUp = false
            startedFor = null
            status.value = Status(PresenceState.NOT_FOUND)
            return
        }
        status.value = Status(PresenceState.STARTING, "opening it in the Spotify app")
        val problem = withContext(Dispatchers.IO) { RootSpotify.play(id) }
        if (problem != null) {
            spotifyUp = false
            startedFor = null
            status.value = Status(PresenceState.ERROR, problem)
            return
        }
        spotifyUp = true
        startedFor = track.id
        status.value = Status(PresenceState.PLAYING, "muted, in the Spotify app")
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
        if (wasUp) scope.launch(Dispatchers.IO) { RootSpotify.stop() }
    }
}
