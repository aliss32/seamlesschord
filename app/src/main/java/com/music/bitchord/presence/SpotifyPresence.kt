package com.music.bitchord.presence

import android.content.Context
import android.media.MediaDrm
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

/**
 * Lets Spotify see what is playing in BitChord, by having Spotify's web player
 * (a WebView of this app, signed in with the account's cookie) play the same
 * track, muted. No other app, no root: the page is the listener.
 *
 * A session goes like this:
 *  1. a track has held for a few seconds → the track is found on Spotify (by
 *     the search API, or the web player's own search page);
 *  2. its page is opened and played; the start only counts once the page's
 *     player reports that it is playing *that* track and moving;
 *  3. while it lasts the page is checked now and then, and started again if
 *     it fell silent;
 *  4. a pause pauses the page at once; a pause that lasts closes the page.
 *
 * Everything here runs on the main thread, where the WebView lives.
 */
object SpotifyPresence {

    data class Status(val state: PresenceState = PresenceState.OFF, val detail: String = "")

    val status = MutableStateFlow(Status())

    /**
     * True while the web page may be playing. BitChord then does not give way
     * to whatever audio focus the page asks for.
     */
    val ignoreFocus = MutableStateFlow(false)

    private data class Track(val id: String, val title: String, val artist: String, val durationSec: Int?)

    private enum class Seen { PLAYING, AD, WRONG, SILENT, SIGNED_OUT }

    private class Outcome(val seen: Seen, val detail: String = "")

    private const val DEBOUNCE_MS = 3_000L
    private const val CLOSE_AFTER_PAUSE_MS = 120_000L
    private const val MONITOR_MS = 15_000L
    private const val MAX_RESTARTS = 2
    private const val SEARCH_URL = "https://open.spotify.com/search/%s/tracks"
    private const val TRACK_URL = "https://open.spotify.com/track/%s"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var latest: Track? = null
    private var latestPlaying = false
    private var debounce: Job? = null
    private var idleClose: Job? = null
    private var work: Job? = null
    private var monitor: Job? = null
    private var player: WebPlayer? = null

    /** The track the page is on, playing or paused. */
    private var playingTrack: String? = null
    private var restartTrack: String? = null
    private var restartCount = 0

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
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
        // Signing in or out of Spotify changes what the page can do.
        scope.launch {
            AppSettings.spotifySpdcToken.drop(1).collect { token ->
                player?.signIn(token)
                if (enabled() && latest != null && latestPlaying) {
                    playingTrack = null
                    debounce?.cancel()
                    debounce = scope.launch { push() }
                }
            }
        }
    }

    fun log(): String = PresenceLog.text()

    fun clearLog() = PresenceLog.clear()

    private fun enabled() = AppSettings.spotifyPresenceEnabled.value

    // ── What BitChord tells us ──────────────────────────────────────────────

    /** Called from the player: on each progress tick while playing, and when playback pauses. */
    fun onPlayback(mediaId: String?, title: String?, artist: String?, durationMs: Long, playing: Boolean) {
        if (!enabled()) return
        val id = mediaId ?: return
        val name = title?.takeIf { it.isNotBlank() } ?: return
        val track = Track(id, name, artist.orEmpty(), (durationMs / 1000).toInt().takeIf { it > 0 })
        // Only the track's identity decides whether this is a change: its length is often reported late or revised.
        if (id == latest?.id && playing == latestPlaying) {
            latest = track
            return
        }
        latest = track
        latestPlaying = playing
        debounce?.cancel()
        // A pause is acted on at once; a new track only once it has held.
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
        idleClose?.cancel()
        if (!latestPlaying) {
            work?.cancel()
            monitor?.cancel()
            if (player != null) status.value = Status(PresenceState.PAUSED)
            scope.launch { pausePage() }
            idleClose = scope.launch {
                delay(CLOSE_AFTER_PAUSE_MS)
                PresenceLog.log("paused for a while; closing the web player")
                closePlayer(PresenceState.PAUSED, "player closed while paused")
            }
            return
        }
        work?.cancel()
        work = if (playingTrack == track.id && player != null) {
            scope.launch { resume(track) }
        } else {
            scope.launch { start(track) }
        }
    }

    // ── Starting a track ────────────────────────────────────────────────────

    private fun createPlayer(): WebPlayer? {
        val ctx = app ?: return null
        return try {
            WebPlayer(ctx).also { it.onRenderGone = { onRenderGone() } }
        } catch (e: Throwable) {
            PresenceLog.log("the web player could not be created: ${e.message}")
            null
        }
    }

    private fun onRenderGone() {
        PresenceLog.log("restarting the web player")
        work?.cancel()
        monitor?.cancel()
        closePlayer(PresenceState.STARTING, "restarting the web player")
        if (enabled() && latestPlaying) {
            debounce?.cancel()
            debounce = scope.launch {
                delay(1_000)
                push()
            }
        }
    }

    /** The track could not be played: whatever the page was on is paused and nothing is left claiming to play. */
    private suspend fun failed(state: PresenceState, detail: String) {
        PresenceLog.log("not playing: $detail")
        pausePage()
        playingTrack = null
        ignoreFocus.value = false
        status.value = Status(state, detail)
    }

    private suspend fun start(track: Track) {
        monitor?.cancel()
        val token = AppSettings.spotifySpdcToken.value
        if (token.isBlank()) {
            failed(PresenceState.SIGN_IN_NEEDED, "connect Spotify in Accounts")
            return
        }
        status.value = Status(PresenceState.SEARCHING, "${track.title} — ${track.artist}")
        PresenceLog.log("track: ${track.title} — ${track.artist} (${track.durationSec ?: "?"} s)")
        val web = player ?: createPlayer().also { player = it }
        if (web == null) {
            failed(PresenceState.ERROR, "the web player is not available on this phone")
            return
        }
        web.signIn(token)
        playingTrack = null
        ignoreFocus.value = true

        val found = findId(web, track)
        if (found == null) {
            failed(PresenceState.NOT_FOUND, "")
            return
        }
        if (found.isEmpty()) {
            failed(PresenceState.SIGN_IN_NEEDED, "Spotify wants you to sign in again")
            return
        }
        status.value = Status(PresenceState.STARTING, "opening it in the web player")

        // The track's own page first; if that does not end with this track playing, a double click on its search row.
        var last = Outcome(Seen.SILENT, "no attempt")
        for (attempt in 1..2) {
            last = if (attempt == 1) playFromTrackPage(web, track, found) else playFromSearchRow(web, track, found)
            PresenceLog.log("attempt $attempt: ${last.seen} ${last.detail.take(100)}")
            if (last.seen == Seen.PLAYING || last.seen == Seen.AD || last.seen == Seen.SIGNED_OUT) break
        }
        when (last.seen) {
            Seen.PLAYING, Seen.AD -> {
                playingTrack = track.id
                restartTrack = restartTrack?.takeIf { it == track.id }
                if (restartTrack == null) restartCount = 0
                status.value = Status(PresenceState.PLAYING, if (last.seen == Seen.AD) "muted, an ad is playing" else "muted")
                watch(track)
            }
            Seen.SIGNED_OUT -> failed(PresenceState.SIGN_IN_NEEDED, "Spotify wants you to sign in again")
            Seen.WRONG -> failed(PresenceState.ERROR, "Spotify played \"${last.detail.take(40)}\" instead")
            Seen.SILENT -> failed(PresenceState.ERROR, "the web player did not start (${last.detail.take(60)})")
        }
    }

    /**
     * The id of the track on Spotify; null when it is not there, an empty
     * string when the page says nobody is signed in.
     */
    private suspend fun findId(web: WebPlayer, track: Track): String? {
        val api = SpotifyLookup.findTrack(track.title, track.artist, track.durationSec)
        if (api.id != null) return api.id
        if (api.error != null) PresenceLog.log("lookup: ${api.error}")
        PresenceLog.log("looking on the web player's search page")
        val query = Uri.encode("${track.title} ${track.artist}".trim())
        if (!web.load(SEARCH_URL.format(query))) {
            PresenceLog.log("the search page did not load")
            return null
        }
        web.mute()
        var rows = emptyList<SpotifyLookup.WebRow>()
        for (poll in 1..20) {
            delay(500)
            val state = web.readState()
            if (state?.loggedOut == true) return ""
            rows = readRows(web)
            if (rows.isNotEmpty()) break
        }
        val id = SpotifyLookup.bestWebMatch(track.title, track.artist, track.durationSec, rows)
        PresenceLog.log("web search: ${rows.size} rows, match ${id ?: "none"}")
        if (id != null) SpotifyLookup.remember(track.title, track.artist, track.durationSec, id)
        return id
    }

    private suspend fun readRows(web: WebPlayer): List<SpotifyLookup.WebRow> {
        val text = web.evalString(WebPlayer.SCRAPE_JS) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val artists = o.optJSONArray("artists")
                SpotifyLookup.WebRow(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    artists = (0 until (artists?.length() ?: 0)).map { artists?.optString(it).orEmpty() },
                    seconds = o.optInt("sec", 0),
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private suspend fun playFromTrackPage(web: WebPlayer, track: Track, id: String): Outcome {
        if (!web.load(TRACK_URL.format(id))) return Outcome(Seen.SILENT, "the track page did not load")
        web.mute()
        var clicked = false
        for (poll in 1..24) {
            if (web.readState()?.loggedOut == true) return Outcome(Seen.SIGNED_OUT)
            if (web.evalString(WebPlayer.CLICK_PLAY_JS) == "clicked") {
                clicked = true
                break
            }
            delay(500)
        }
        if (!clicked) return Outcome(Seen.SILENT, "no play button on the track page")
        web.mute()
        return awaitPlaying(web, track.title, 20_000)
    }

    private suspend fun playFromSearchRow(web: WebPlayer, track: Track, id: String): Outcome {
        val query = Uri.encode("${track.title} ${track.artist}".trim())
        if (!web.load(SEARCH_URL.format(query))) return Outcome(Seen.SILENT, "the search page did not load")
        web.mute()
        var clicked = false
        for (poll in 1..20) {
            if (web.readState()?.loggedOut == true) return Outcome(Seen.SIGNED_OUT)
            if (web.evalString(WebPlayer.doubleClickJs(id)) == "clicked") {
                clicked = true
                break
            }
            delay(500)
        }
        if (!clicked) return Outcome(Seen.SILENT, "the track was not among the search rows")
        web.mute()
        return awaitPlaying(web, track.title, 20_000)
    }

    /**
     * Waits for the page to report that it is playing *this* track and moving.
     * "Playing" with another title is not a success, and neither is a player
     * that merely says it started.
     */
    private suspend fun awaitPlaying(web: WebPlayer, title: String, timeoutMs: Long): Outcome {
        val want = SpotifyLookup.normalize(title)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous = -1.0
        var wrongRun = 0
        var last: WebPlayer.WebState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(700)
            val state = web.readState() ?: continue
            last = state
            if (state.loggedOut) return Outcome(Seen.SIGNED_OUT)
            val advancing = previous >= 0 && state.seconds > previous + 0.2
            previous = state.seconds
            if (!state.playing) {
                wrongRun = 0
                continue
            }
            val shown = state.shown
            if (SpotifyLookup.isAd(shown)) return Outcome(Seen.AD, shown)
            if (state.sessionState != "playing" && !advancing) continue
            if (shown.isBlank()) {
                PresenceLog.log("playing, but the page names no track; trusting the page that was opened")
                return Outcome(Seen.PLAYING)
            }
            if (SpotifyLookup.looseMatch(want, SpotifyLookup.normalize(shown))) return Outcome(Seen.PLAYING, shown)
            if (++wrongRun >= 3) return Outcome(Seen.WRONG, shown)
        }
        return Outcome(Seen.SILENT, last?.toString() ?: "the page did not answer")
    }

    // ── While it lasts ──────────────────────────────────────────────────────

    /** The same track again after a pause: the page is told to play on, or the track is started afresh. */
    private suspend fun resume(track: Track) {
        val web = player
        if (web == null) {
            start(track)
            return
        }
        status.value = Status(PresenceState.STARTING, "resuming")
        ignoreFocus.value = true
        if (web.readState()?.playing != true) web.evalString(WebPlayer.TOGGLE_JS)
        val result = awaitPlaying(web, track.title, 8_000)
        if (result.seen == Seen.PLAYING || result.seen == Seen.AD) {
            status.value = Status(PresenceState.PLAYING, "muted")
            watch(track)
        } else {
            PresenceLog.log("it did not resume (${result.seen}); starting the track again")
            start(track)
        }
    }

    private fun watch(track: Track) {
        monitor?.cancel()
        monitor = scope.launch {
            var silent = 0
            var other = 0
            while (isActive) {
                delay(MONITOR_MS)
                val web = player ?: return@launch
                if (playingTrack != track.id || !latestPlaying) return@launch
                val state = web.readState()
                if (state?.loggedOut == true) {
                    failed(PresenceState.SIGN_IN_NEEDED, "Spotify wants you to sign in again")
                    return@launch
                }
                val shown = state?.shown.orEmpty()
                when {
                    state == null || !state.playing -> {
                        other = 0
                        if (++silent < 2) continue
                        if (restartTrack == track.id) restartCount++ else { restartTrack = track.id; restartCount = 1 }
                        if (restartCount > MAX_RESTARTS) {
                            failed(PresenceState.ERROR, "the web player keeps stopping")
                            return@launch
                        }
                        PresenceLog.log("the web player stopped; starting it again ($restartCount)")
                        work?.cancel()
                        work = scope.launch { start(track) }
                        return@launch
                    }
                    SpotifyLookup.isAd(shown) ||
                        SpotifyLookup.looseMatch(SpotifyLookup.normalize(track.title), SpotifyLookup.normalize(shown)) -> {
                        silent = 0
                        other = 0
                        status.value = Status(PresenceState.PLAYING, if (SpotifyLookup.isAd(shown)) "muted, an ad is playing" else "muted")
                    }
                    else -> {
                        silent = 0
                        // Spotify went on to another track by itself (its copy ended first): it is not left claiming that.
                        if (++other >= 2) {
                            PresenceLog.log("the web player moved on to \"${shown.take(50)}\"; pausing it")
                            pausePage()
                            status.value = Status(PresenceState.PAUSED, "Spotify's copy of the track ended")
                            return@launch
                        }
                    }
                }
            }
        }
    }

    private suspend fun pausePage() {
        val web = player ?: return
        if (web.readState()?.playing != true) return
        web.evalString(WebPlayer.TOGGLE_JS)
        delay(1_200)
        if (web.readState()?.playing == true) web.evalString(WebPlayer.HARD_PAUSE_JS)
    }

    // ── Ending ──────────────────────────────────────────────────────────────

    /** Closes the page and everything that came with it. The caller sets whether the jobs go on. */
    private fun closePlayer(state: PresenceState, detail: String = "") {
        player?.destroy()
        player = null
        playingTrack = null
        ignoreFocus.value = false
        status.value = Status(state, detail)
    }

    private fun cancelAll() {
        idleClose?.cancel()
        debounce?.cancel()
        work?.cancel()
        monitor?.cancel()
    }

    /** The player is going away (or the app was swiped off): the web page goes too. */
    fun endSession() {
        cancelAll()
        closePlayer(if (enabled()) PresenceState.PAUSED else PresenceState.OFF, if (enabled()) "player closed" else "")
    }

    /** The feature was turned off. */
    private fun shutdown() {
        cancelAll()
        closePlayer(PresenceState.OFF)
    }

    // ── For the log screen ──────────────────────────────────────────────────

    /** Plays the current track again from the start, as if it had just begun. */
    fun restart() {
        if (!enabled()) {
            PresenceLog.log("restart: the feature is off")
            return
        }
        val track = latest
        if (track == null || !latestPlaying) {
            PresenceLog.log("restart: nothing is playing in BitChord")
            return
        }
        PresenceLog.log("restart requested")
        playingTrack = null
        debounce?.cancel()
        debounce = scope.launch { push() }
    }

    /** Puts the web page in [container] for a look; true when it could. */
    fun showPlayer(host: Context, container: ViewGroup): Boolean {
        val web = player ?: createPlayer()?.also {
            player = it
            it.signIn(AppSettings.spotifySpdcToken.value)
            scope.launch { it.load("https://open.spotify.com/") }
        } ?: return false
        web.attachTo(container, host)
        return true
    }

    /** Takes the page back out of [container]; when nothing is playing it is closed. */
    fun hidePlayer(container: ViewGroup) {
        player?.detachFrom(container)
        if (playingTrack == null && work?.isActive != true) {
            player?.destroy()
            player = null
        }
    }

    /** What this phone and the page allow, written into the log. */
    fun selfTest() {
        scope.launch {
            PresenceLog.log("── self-test ──")
            val token = AppSettings.spotifySpdcToken.value
            PresenceLog.log("test spotify cookie: ${if (token.isBlank()) "MISSING" else "present"}")
            PresenceLog.log("test android: ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT}), ${Build.MODEL}")
            if (Build.VERSION.SDK_INT >= 26) {
                val pkg = WebView.getCurrentWebViewPackage()
                PresenceLog.log("test webview: ${pkg?.packageName} ${pkg?.versionName}")
            }
            @Suppress("DEPRECATION")
            val drm = runCatching {
                val d = MediaDrm(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
                val text = "${d.getPropertyString("vendor")} ${d.getPropertyString("version")}, level ${d.getPropertyString("securityLevel")}"
                d.release()
                text
            }.getOrElse { "MediaDrm failed: ${it.message}" }
            PresenceLog.log("test widevine (system): $drm")
            val temporary = player == null
            val web = player ?: createPlayer()?.also { player = it }
            if (web == null) {
                PresenceLog.log("test: the web player could not be created")
                return@launch
            }
            PresenceLog.log("test user agent: ${web.userAgent.take(90)}")
            if (temporary) {
                web.signIn(token)
                val loaded = web.load("https://open.spotify.com/")
                PresenceLog.log("test page: ${if (loaded) "loaded" else "did not load"}")
                delay(3_000)
            }
            web.evalString(WebPlayer.DRM_JS)
            delay(1_500)
            PresenceLog.log("test widevine (page): ${web.evalString(WebPlayer.READ_DRM_JS) ?: "no answer"}")
            val state = web.readState()
            PresenceLog.log("test page state: ${state ?: "no answer"}")
            if (state?.loggedOut == true) PresenceLog.log("test: the page is signed out; connect Spotify again in Accounts")
            if (temporary && playingTrack == null && work?.isActive != true) {
                web.destroy()
                player = null
            }
            PresenceLog.log("── end of self-test ──")
        }
    }
}
