package com.music.bitchord.presence

import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Drives the installed Spotify app, as root, so that Spotify itself plays the
 * track BitChord is playing and shows it as the listener's activity.
 *
 * Root is what makes it silent and invisible: the Spotify app's audio is muted
 * at the system (appops `PLAY_AUDIO`), it is kept from taking audio focus away
 * from BitChord (`TAKE_AUDIO_FOCUS`), and its screen is sent straight back
 * after it has started the track. Without root none of that is possible, so
 * the feature does nothing at all.
 *
 * Both appops are put back to their defaults whenever the session ends, so
 * the Spotify app is never left muted for the times it is used by hand.
 */
internal object RootSpotify {

    const val PACKAGE = "com.spotify.music"

    private const val LOG_LINES = 150
    private const val DURATION_TOLERANCE_SEC = 8
    private const val MAX_WAIT_MS = 30_000L
    private const val MAX_RATE_LIMIT_TRIES = 3

    private val lines = ArrayDeque<String>()

    // The log has a lock of its own: a root command can take many seconds and the log is read from the screen.
    private val logLock = Any()
    private val shellLock = Any()

    fun log(line: String) {
        synchronized(logLock) {
            lines.addLast(line)
            while (lines.size > LOG_LINES) lines.removeFirst()
        }
    }

    fun recentLog(): String = synchronized(logLock) { lines.joinToString("\n") }

    class Shell(val code: Int, val out: String)

    /** Runs a script as root. Never throws; a missing or refused `su` comes back as a negative code. */
    fun su(script: String, timeoutSec: Long = 20): Shell {
        return try {
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            runCatching { process.outputStream.close() }
            val output = StringBuffer()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') }
                }
            }
            reader.start()
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.join(500)
                return Shell(-1, output.toString().trim() + "\n(timed out)")
            }
            reader.join(1_000)
            Shell(process.exitValue(), output.toString().trim())
        } catch (e: Exception) {
            Shell(-2, "su is not available: ${e.message}")
        }
    }

    /** Null when root is granted and the Spotify app is installed; otherwise what is missing. */
    fun check(): String? {
        val result = su("id -u; pm path $PACKAGE")
        log("check: code ${result.code} ${result.out.replace('\n', ' ').take(120)}")
        if (result.code != 0 || result.out.lines().firstOrNull()?.trim() != "0") return "root access was not granted"
        if (!result.out.contains("package:")) return "the Spotify app is not installed"
        return null
    }

    /** Single-quotes [text] for the shell, whatever it contains. */
    private fun quoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** Opens that one track and plays it. */
    fun play(trackId: String, displayId: Int?): String? =
        launch("-a android.intent.action.VIEW -d spotify:track:$trackId:play", displayId, "track $trackId")

    /**
     * For when the track's id cannot be looked up: asks the Spotify app to
     * search for it and play the best match, the way a voice assistant would.
     */
    fun playFromSearch(title: String, artist: String, displayId: Int?): String? {
        val args = "-a android.media.action.MEDIA_PLAY_FROM_SEARCH " +
            "--es query ${quoted("$title $artist".trim())} " +
            "--es android.intent.extra.focus vnd.android.cursor.item/audio " +
            "--es android.intent.extra.title ${quoted(title)} " +
            "--es android.intent.extra.artist ${quoted(artist)}"
        return launch(args, displayId, "search")
    }

    /** Where the app's own files are: the watchdog reads the heartbeat file from here. Set once at start-up. */
    @Volatile
    var beatFile: String? = null

    @Volatile
    var ownPackage: String = "com.seamless.chord"

    private const val WATCHDOG_PID = "/data/local/tmp/seamless-watchdog.pid"
    private const val STATE_PLAYING = 3
    private const val POLLS = 10
    private const val POLL_MS = 1_500L

    /** What the Spotify app's media session says it is doing (3 = playing); null when it has no session. */
    private fun mediaState(): Int? {
        val dump = su("dumpsys media_session", 15).out
        var inSpotify = false
        for (line in dump.lines()) {
            if (line.contains("package=")) inSpotify = line.contains(PACKAGE)
            if (inSpotify && line.contains("state=PlaybackState")) {
                return Regex("""state=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        return null
    }

    /** The screens Spotify is the resumed app on ("0" is the real one). */
    private fun spotifyDisplays(): Set<String> {
        val dump = su("dumpsys activity activities", 15).out
        var display = "?"
        val found = mutableSetOf<String>()
        for (line in dump.lines()) {
            Regex("""^\s*Display #(\d+)""").find(line)?.let { display = it.groupValues[1] }
            if (line.contains("ResumedActivity") && line.contains(PACKAGE)) found += display
        }
        return found
    }

    /** What is worth knowing when Spotify did not play: its process state, the focus stack and its audio events. */
    private fun diagnose(state: Int?, displays: Set<String>) {
        log("  media session state: ${state ?: "no session"}; resumed on display: ${displays.ifEmpty { setOf("none") }.joinToString()}")
        val uid = Regex("""uid:(\d+)""").find(su("pm list packages -U $PACKAGE").out)?.groupValues?.get(1)
        if (uid != null) {
            log("  uid $uid state: " + su("cmd activity get-uid-state $uid || am get-uid-state $uid", 8).out.lines().lastOrNull().orEmpty().take(100))
        }
        val audio = su("dumpsys audio", 15).out.lines()
        val focus = audio.indexOfFirst { it.contains("Audio Focus stack", ignoreCase = true) }
        if (focus >= 0) audio.drop(focus).take(3).forEach { log("  ${it.trim().take(140)}") }
        // The newest lines last: the old ones are not what this attempt did.
        audio.filter { it.contains("spotify", ignoreCase = true) || it.contains("AudioHardening") }
            .takeLast(6).forEach { log("  ${it.trim().take(160)}") }
    }

    /**
     * Muted, started on the hidden screen when there is one, then checked:
     * the Spotify app has to report that it really is playing. Returns null on
     * success, or what went wrong.
     */
    private fun launch(intentArgs: String, displayId: Int?, what: String): String? = synchronized(shellLock) {
        val display = if (displayId != null) "--display $displayId " else ""
        // Muted at the system, but allowed to take audio focus: a Spotify that is refused focus does not play at all.
        val script = """
            cmd appops set $PACKAGE PLAY_AUDIO deny
            cmd appops set $PACKAGE TAKE_AUDIO_FOCUS allow
            am start $display$intentArgs -p $PACKAGE --activity-no-animation
            cmd appops get $PACKAGE PLAY_AUDIO
        """.trimIndent()
        val result = su(script, timeoutSec = 30)
        log("play ($what, display ${displayId ?: "real"}): code ${result.code}")
        result.out.lines().filter { it.isNotBlank() }.takeLast(6).forEach { log("  $it") }
        if (result.code < 0) return result.out
        if (result.out.contains("Error", ignoreCase = true) && result.out.contains("not started", ignoreCase = true)) {
            return "the Spotify app did not accept the request"
        }
        if (!result.out.contains("deny", ignoreCase = true)) {
            log("  warning: PLAY_AUDIO does not read as denied; the track may be audible")
        }
        var state: Int? = null
        var displays = emptySet<String>()
        for (poll in 1..POLLS) {
            Thread.sleep(POLL_MS)
            displays = spotifyDisplays()
            // Asked for the hidden screen but it came up on the real one: out of the way at once.
            if (displayId != null && "0" in displays) {
                log("  spotify is on the real screen; sending Back")
                su("input keyevent 4")
            }
            state = mediaState()
            if (state == STATE_PLAYING) {
                log("  spotify is playing (poll $poll)")
                // On the real screen on purpose: it has to be the app in front until it plays. Now it can go.
                if (displayId == null && "0" in displays) {
                    Thread.sleep(800)
                    su("input keyevent 4")
                    log("  sent Back")
                }
                return null
            }
        }
        diagnose(state, displays)
        return "Spotify did not start playing (state ${state ?: "no session"})"
    }

    /**
     * A small root script that outlives this app. While the app is alive and
     * keeps touching its heartbeat file, it does nothing; the moment the app
     * is gone (swiped away, killed, crashed) or goes quiet, it stops Spotify
     * and puts its sound back. This is what makes sure Spotify is never left
     * muted, whatever happens to the app.
     */
    fun startWatchdog() {
        val beat = beatFile ?: return
        val script = """
            [ -f $WATCHDOG_PID ] && kill ${'$'}(cat $WATCHDOG_PID) 2>/dev/null
            setsid nohup sh -c '
              echo ${'$'}${'$'} > $WATCHDOG_PID
              while true; do
                sleep 3
                pid=${'$'}(pidof $ownPackage)
                last=${'$'}(stat -c %Y "$beat" 2>/dev/null || echo 0)
                now=${'$'}(date +%s)
                if [ -z "${'$'}pid" ] || [ ${'$'}((now - last)) -gt 40 ]; then
                  am force-stop $PACKAGE
                  cmd appops set $PACKAGE PLAY_AUDIO default
                  cmd appops set $PACKAGE TAKE_AUDIO_FOCUS default
                  rm -f $WATCHDOG_PID
                  exit 0
                fi
              done
            ' >/dev/null 2>&1 </dev/null &
        """.trimIndent()
        val result = su(script, 10)
        log("watchdog: code ${result.code}")
    }

    /** Whether the hidden screen has been tried and found not to work on this phone (kept until the app restarts). */
    @Volatile
    var preferReal = false

    /** Stops the Spotify app without touching its sound settings: for starting over on another screen. */
    fun forceStop() {
        synchronized(shellLock) { su("am force-stop $PACKAGE") }
    }

    /** Stops Spotify, puts its audio settings back and retires the watchdog. */
    fun stop() {
        synchronized(shellLock) {
            val result = su(
                "[ -f $WATCHDOG_PID ] && kill \$(cat $WATCHDOG_PID) 2>/dev/null; rm -f $WATCHDOG_PID; " +
                    "am force-stop $PACKAGE; " +
                    "cmd appops set $PACKAGE PLAY_AUDIO default; " +
                    "cmd appops set $PACKAGE TAKE_AUDIO_FOCUS default",
            )
            log("stop: code ${result.code}")
        }
    }

    /** Only puts the audio settings back, for the times Spotify is used by hand. */
    fun restoreSound() {
        synchronized(shellLock) {
            val result = su(
                "cmd appops set $PACKAGE PLAY_AUDIO default; cmd appops set $PACKAGE TAKE_AUDIO_FOCUS default",
            )
            log("restore sound: code ${result.code}")
        }
    }

    // ── Finding the track on Spotify ────────────────────────────────────────

    class Lookup(val id: String?, val error: String? = null, val limited: Boolean = false)

    /** Until when the search API is left alone, after it said it was being asked too much. */
    @Volatile
    var apiBlockedUntil = 0L

    /** Tracks already matched, so skipping back and forth does not ask Spotify twice. */
    private val matched = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    suspend fun findTrack(title: String, artist: String, durationSec: Int?): Lookup {
        val key = "${normalize(title)}|${normalize(artist)}|${durationSec ?: 0}"
        synchronized(matched) { matched[key] }?.let {
            log("search: cached match $it")
            return Lookup(it)
        }
        var token = withContext(Dispatchers.IO) { SpotifyToken.accessToken() }
        if (token.isNullOrBlank()) return Lookup(null, "Spotify sign-in is needed")
        val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        var refreshed = false
        var rateLimited = 0
        while (true) {
            // Just asked and told to wait: the answer comes with a time, and that time is short. Waiting it out
            // beats the Spotify app's own search, which does not reliably start anything.
            val resting = apiBlockedUntil - System.currentTimeMillis()
            if (resting > 0) {
                if (resting > MAX_WAIT_MS) return Lookup(null, "search API is resting", limited = true)
                delay(resting + 500)
            }
            val reply = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL("https://api.spotify.com/v1/search?type=track&limit=10&q=$query")
                        .openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    val code = connection.responseCode
                    if (code == 429) {
                        val wait = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 20L
                        apiBlockedUntil = System.currentTimeMillis() + wait.coerceIn(5L, 120L) * 1_000L
                        log("search: HTTP 429, Retry-After ${wait}s")
                    }
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    code to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
            }.getOrElse { return Lookup(null, "Spotify search failed: ${it.message}", limited = true) }
            val (code, body) = reply
            if (code == 429) {
                if (++rateLimited >= MAX_RATE_LIMIT_TRIES) {
                    return Lookup(null, "Spotify search is rate limited", limited = true)
                }
                continue
            }
            if (code == 401 && !refreshed) {
                refreshed = true
                SpotifyToken.invalidate()
                token = withContext(Dispatchers.IO) { SpotifyToken.accessToken() }
                if (token.isNullOrBlank()) return Lookup(null, "Spotify sign-in is needed")
                continue
            }
            if (code !in 200..299) {
                log("search: HTTP $code ${body.take(160)}")
                return Lookup(null, "Spotify search failed (HTTP $code)", limited = true)
            }
            val items = runCatching { JSONObject(body).optJSONObject("tracks")?.optJSONArray("items") }.getOrNull()
                ?: JSONArray()
            val id = bestMatch(title, artist, durationSec, items)
            log("search: ${items.length()} results, match ${id ?: "none"}")
            if (id != null) synchronized(matched) { matched[key] = id }
            return Lookup(id)
        }
    }

    private fun bestMatch(title: String, artist: String, durationSec: Int?, items: JSONArray): String? {
        val wantTitle = normalize(title)
        val wantArtists = artistsOf(artist)
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (k in 0 until items.length()) {
            val item = items.optJSONObject(k) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val got = normalize(item.optString("name"))
            if (got.isBlank() || wantTitle.isBlank()) continue
            val titleScore = when {
                got == wantTitle -> 3
                got.contains(wantTitle) || wantTitle.contains(got) -> 1
                else -> 0
            }
            if (titleScore == 0) continue
            val credited = item.optJSONArray("artists")
            val gotArtists = (0 until (credited?.length() ?: 0))
                .map { normalize(credited?.optJSONObject(it)?.optString("name")) }
                .filter { it.isNotBlank() }
            val artistOk = wantArtists.isEmpty() || gotArtists.isEmpty() ||
                wantArtists.any { a -> gotArtists.any { g -> g == a || g.contains(a) || a.contains(g) } }
            if (!artistOk) continue
            var score = titleScore * 100
            val seconds = item.optInt("duration_ms", -1) / 1000
            if (seconds > 0 && durationSec != null) {
                val gap = abs(seconds - durationSec)
                if (gap > DURATION_TOLERANCE_SEC) continue
                score -= gap
            }
            if (score > bestScore) {
                bestScore = score
                best = id
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
}
