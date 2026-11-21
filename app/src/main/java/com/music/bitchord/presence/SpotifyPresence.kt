package com.music.bitchord.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lets Spotify see what is playing in BitChord, by having the Spotify app play
 * the same track, muted and out of sight (see [RootSpotify], which needs root).
 *
 * The life of a session:
 *  1. a track has held for a few seconds → Spotify is made ready (muted, exempt
 *     from battery limits) and started on a screen that exists only in memory;
 *  2. the start is only called a success once Spotify's own media session says
 *     it is playing *that* track;
 *  3. while it lasts, Spotify is checked every so often and started again if it
 *     fell silent;
 *  4. when playback pauses for good, the player closes or the app is swiped
 *     away, Spotify is stopped and its sound put back — and if the app dies
 *     before it can, a root watchdog and a boot script do it instead.
 *
 * With the screen on and no hidden screen to use, Spotify is not shown on the
 * real one (unless the listener allowed that); it waits for the screen to go off.
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
    private const val STOP_AFTER_PAUSE_MS = 20_000L
    private const val MONITOR_MS = 20_000L
    private const val MAX_RESTARTS = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var latest: Track? = null
    private var latestPlaying = false
    private var debounce: Job? = null
    private var idleStop: Job? = null
    private var work: Job? = null
    private var monitor: Job? = null
    private var spotifyUp = false
    private var startedFor: String? = null
    private var rootChecked = false
    private var beat: Job? = null
    private var restartTrack: String? = null
    private var restartCount = 0
    private var screenReceiver: BroadcastReceiver? = null

    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        RootSpotify.appContext = ctx
        RootSpotify.ownPackage = ctx.packageName
        RootSpotify.beatFile = File(ctx.filesDir, "presence.beat").absolutePath
        // The app was killed while Spotify was muted: its sound comes back now, whatever else happens.
        if (RootSpotify.dirty) {
            RootSpotify.log("a previous session was left muted; putting Spotify's sound back")
            scope.launch(Dispatchers.IO) { RootSpotify.restoreSound() }
        }
        watchScreen(ctx)
        scope.launch {
            AppSettings.spotifyPresenceEnabled.collect { on ->
                if (!on) {
                    shutdown()
                } else if (latest != null && latestPlaying) {
                    debounce?.cancel()
                    debounce = scope.launch { push() }
                }
            }
        }
    }

    /** With the screen off nobody can see Spotify, so a start that was waiting for that goes ahead. */
    private fun watchScreen(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!enabled() || !latestPlaying || spotifyUp) return
                debounce?.cancel()
                debounce = scope.launch {
                    delay(1_500)
                    push()
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiver = receiver
        }
    }

    fun log(): String = RootSpotify.recentLog()

    /** For when Spotify was left muted: puts its sound back. */
    fun restoreSound() {
        scope.launch(Dispatchers.IO) { RootSpotify.restoreSound() }
    }

    /** The self-test button: what this phone allows, written into the log. */
    fun selfTest() {
        scope.launch(Dispatchers.IO) {
            val hidden = if (spotifyUp) {
                "in use by the session"
            } else {
                val ctx = app
                if (ctx == null) {
                    "no context"
                } else {
                    val id = VirtualScreen.open(ctx)
                    VirtualScreen.close()
                    if (id != null) "works (id $id)" else "REFUSED by the system"
                }
            }
            RootSpotify.selfTest(hidden)
        }
    }

    /** Whether Spotify may flash on the real screen when there is no hidden one. */
    var allowFlash: Boolean
        get() = RootSpotify.allowFlash
        set(value) { RootSpotify.allowFlash = value }

    private fun enabled() = AppSettings.spotifyPresenceEnabled.value

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
        if (spotifyUp && startedFor == track.id) return
        work?.cancel()
        work = scope.launch { start(track) }
    }

    /** Everything that was started for a session that did not come to anything. */
    private suspend fun abandon(state: PresenceState, detail: String) {
        withContext(Dispatchers.IO) { RootSpotify.stop() }
        stopKeepAlive()
        VirtualScreen.close()
        spotifyUp = false
        startedFor = null
        ignoreFocus.value = false
        status.value = Status(state, detail)
    }

    private suspend fun start(track: Track) {
        monitor?.cancel()
        val ticket = RootSpotify.newTicket()
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
        // The search API failing is not the end: the Spotify app can find the track on its own screen.
        val fallback = lookup.limited || lookup.error != null
        if (lookup.id == null && !fallback) {
            // Whatever Spotify was last playing must not stay up as if it were this.
            if (spotifyUp) withContext(Dispatchers.IO) { RootSpotify.stop() }
            stopKeepAlive()
            VirtualScreen.close()
            spotifyUp = false
            startedFor = null
            ignoreFocus.value = false
            status.value = Status(PresenceState.NOT_FOUND)
            return
        }

        // Which screens Spotify may be started on: the hidden one first; the real one only while nobody can see it.
        val screenOn = RootSpotify.screenOn()
        val hidden = app?.let { withContext(Dispatchers.IO) { VirtualScreen.open(it) } }
        val screens = mutableListOf<Int?>()
        if (hidden != null) screens += hidden
        if (!screenOn || RootSpotify.allowFlash) screens += null
        if (screens.isEmpty()) {
            RootSpotify.log("no hidden screen on this phone and the screen is on: waiting for it to go off (or allow the flash in the log screen)")
            abandon(PresenceState.ERROR, "waiting for the screen to turn off (no hidden screen)")
            return
        }

        status.value = Status(PresenceState.STARTING, "opening it in the Spotify app")
        ignoreFocus.value = true
        // The watchdog comes first: from the moment Spotify is muted, something must be there to unmute it.
        withContext(Dispatchers.IO) { startKeepAlive() }
        val prepared = withContext(Dispatchers.IO) { RootSpotify.prepare() }
        if (prepared != null) {
            abandon(PresenceState.ERROR, prepared)
            return
        }
        val problem = withContext(Dispatchers.IO) {
            var result: String? = "no attempt"
            for ((index, display) in screens.withIndex()) {
                if (index > 0) {
                    RootSpotify.log("not playing on the hidden screen (${result?.take(60)}); stopping Spotify, trying the real screen")
                    RootSpotify.forceStop()
                }
                result = RootSpotify.startPlayback(lookup.id, track.title, track.artist, display, ticket)
                if (result == null || result == "cancelled") break
            }
            result
        }
        if (problem == "cancelled") return
        if (problem != null) {
            // Not playing: nothing is left half-started, muted or holding the player's focus.
            abandon(PresenceState.ERROR, problem)
            return
        }
        spotifyUp = true
        startedFor = track.id
        status.value = Status(PresenceState.PLAYING, "muted")
        watch(track)
    }

    /** While the session lasts: is Spotify still playing? If it fell silent, it is started again (a couple of times). */
    private fun watch(track: Track) {
        monitor?.cancel()
        monitor = scope.launch {
            var silent = 0
            while (isActive) {
                delay(MONITOR_MS)
                if (!spotifyUp || startedFor != track.id || !latestPlaying) return@launch
                when (withContext(Dispatchers.IO) { RootSpotify.health(track.title) }) {
                    RootSpotify.Health.OK, RootSpotify.Health.AD -> {
                        silent = 0
                        status.value = Status(PresenceState.PLAYING, "muted")
                    }
                    // Spotify went on to something else by itself (its track ended first): the next change of track fixes it.
                    RootSpotify.Health.OTHER -> silent = 0
                    RootSpotify.Health.SILENT -> {
                        if (++silent < 2) continue
                        if (restartTrack == track.id) restartCount++ else { restartTrack = track.id; restartCount = 1 }
                        if (restartCount > MAX_RESTARTS) {
                            RootSpotify.log("monitor: Spotify keeps stopping; giving up on this track")
                            abandon(PresenceState.ERROR, "Spotify keeps stopping")
                            return@launch
                        }
                        RootSpotify.log("monitor: Spotify is not playing any more; starting it again ($restartCount)")
                        spotifyUp = false
                        startedFor = null
                        work?.cancel()
                        work = scope.launch { start(track) }
                        return@launch
                    }
                }
            }
        }
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

    private fun resetState() {
        idleStop?.cancel()
        debounce?.cancel()
        work?.cancel()
        monitor?.cancel()
        spotifyUp = false
        startedFor = null
        rootChecked = false
        status.value = Status()
        stopKeepAlive()
        ignoreFocus.value = false
        VirtualScreen.close()
    }

    /**
     * The player is going away (or the app was swiped off): Spotify, its hidden
     * screen and its audio settings go too. The root command runs on a thread of
     * its own, which outlives this call and the process.
     */
    fun endSession() {
        val needsCleanup = spotifyUp || RootSpotify.dirty
        resetState()
        RootSpotify.cancelAttempts()
        if (needsCleanup) Thread { RootSpotify.stop() }.start()
    }

    /** Ends the session: Spotify is stopped and its audio settings are put back. */
    private fun shutdown() {
        val needsCleanup = spotifyUp || RootSpotify.dirty
        resetState()
        RootSpotify.cancelAttempts()
        if (needsCleanup) scope.launch(Dispatchers.IO) { RootSpotify.stop() }
    }
}
