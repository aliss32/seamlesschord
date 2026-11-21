package com.music.bitchord.presence

import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
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

    private const val LOG_LINES = 400
    private const val DURATION_TOLERANCE_SEC = 8
    private const val MAX_WAIT_MS = 8_000L
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


    /** Set once at start-up; needed for the screen state and the small bits of memory kept between runs. */
    @Volatile
    var appContext: android.content.Context? = null

    /** Each start-up attempt holds a ticket; a newer attempt (or a stop) makes the older ones give up at their next step. */
    private val tickets = java.util.concurrent.atomic.AtomicInteger(0)

    fun newTicket(): Int = tickets.incrementAndGet()

    fun cancelAttempts() {
        tickets.incrementAndGet()
    }

    private fun stale(ticket: Int) = tickets.get() != ticket

    fun screenOn(): Boolean {
        val pm = appContext?.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isInteractive ?: true
    }

    private fun prefs() = appContext?.getSharedPreferences("seamless_presence", android.content.Context.MODE_PRIVATE)

    /** Whether Spotify may be shown on the real screen for a moment when the hidden screen is not available. Off by default. */
    var allowFlash: Boolean
        get() = prefs()?.getBoolean("allow_flash", false) ?: false
        set(value) { prefs()?.edit()?.putBoolean("allow_flash", value)?.apply() }

    /** True from the moment Spotify is muted until its sound is put back: survives the app being killed. */
    var dirty: Boolean
        get() = prefs()?.getBoolean("dirty", false) ?: false
        set(value) { prefs()?.edit()?.putBoolean("dirty", value)?.apply() }

    private var lastGoodRung: String
        get() = prefs()?.getString("good_rung", "").orEmpty()
        set(value) { prefs()?.edit()?.putString("good_rung", value)?.apply() }

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

    // ── State shared by the steps below ─────────────────────────────────────

    /** Where the app's own files are: the watchdog reads the heartbeat file from here. Set once at start-up. */
    @Volatile
    var beatFile: String? = null

    @Volatile
    var ownPackage: String = "com.seamless.chord"

    private const val WATCHDOG_PID = "/data/local/tmp/seamless-watchdog.pid"
    private const val BOOT_SCRIPT = "/data/adb/service.d/seamless-unmute.sh"
    private const val STATE_PLAYING = 3
    private const val POLLS = 10
    private const val POLL_MS = 1_500L
    private const val RESTORE_SOUND =
        "cmd appops set $PACKAGE PLAY_AUDIO default; cmd appops set $PACKAGE TAKE_AUDIO_FOCUS default"

    /** Labels of the green play button on a track page, in the languages the app is likely to be in. */
    private val PLAY_LABELS = setOf("play", "oynat", "çal", "shuffle play", "karışık çal", "karisik cal", "resume", "devam")

    // ── Getting the phone ready ─────────────────────────────────────────────

    /**
     * One root script that does everything Spotify needs to keep playing with
     * the screen off and to stay silent: no notification prompt, exempt from
     * Doze and background limits, muted at the system but allowed audio focus.
     * It also leaves a tiny boot script behind that puts the sound back if the
     * phone restarts mid-session (appops outlive a reboot).
     */
    fun prepare(): String? {
        dirty = true
        val script = """
            pm grant $PACKAGE android.permission.POST_NOTIFICATIONS 2>/dev/null
            cmd deviceidle whitelist +$PACKAGE 2>/dev/null
            cmd deviceidle whitelist +$ownPackage 2>/dev/null
            cmd appops set $PACKAGE RUN_IN_BACKGROUND allow
            cmd appops set $PACKAGE RUN_ANY_IN_BACKGROUND allow
            am set-standby-bucket $PACKAGE active 2>/dev/null
            am set-standby-bucket $ownPackage active 2>/dev/null
            mkdir -p /data/adb/service.d 2>/dev/null
            cat > $BOOT_SCRIPT <<'EOS'
            #!/system/bin/sh
            until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
            sleep 8
            cmd appops set $PACKAGE PLAY_AUDIO default
            cmd appops set $PACKAGE TAKE_AUDIO_FOCUS default
            EOS
            chmod 755 $BOOT_SCRIPT 2>/dev/null
            cmd appops set $PACKAGE PLAY_AUDIO deny
            cmd appops set $PACKAGE TAKE_AUDIO_FOCUS allow
            cmd appops get $PACKAGE PLAY_AUDIO
        """.trimIndent()
        val result = su(script, 30)
        log("prepare: code ${result.code}; ${result.out.lines().lastOrNull().orEmpty().take(100)}")
        if (result.code < 0) return result.out
        if (!result.out.contains("deny", ignoreCase = true)) {
            log("  warning: PLAY_AUDIO does not read as denied; the track may be audible")
        }
        return null
    }

    // ── Reading what Spotify is doing ───────────────────────────────────────

    /** Spotify's media session: its state (3 = playing) and the "title, artist, album" line it publishes. */
    class Snap(val state: Int?, val desc: String, val block: String)

    fun snapshot(): Snap {
        val lines = su("dumpsys media_session", 15).out.lines()
        val start = lines.indexOfFirst { it.contains("package=$PACKAGE") }
        if (start < 0) return Snap(null, "", "")
        var end = lines.size
        for (i in start + 1 until lines.size) {
            if (lines[i].contains("package=")) { end = i; break }
        }
        val block = lines.subList(start, end).joinToString("\n")
        val state = Regex("""PlaybackState\s*\{\s*state=(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\bstate=(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()
        val desc = Regex("""description=([^\n]*)""").find(block)?.groupValues?.get(1).orEmpty().trim()
        return Snap(state, desc, block)
    }

    enum class Health { OK, AD, OTHER, SILENT }

    /** For the check that runs while a session is up: is Spotify still playing this track? */
    fun health(title: String): Health {
        val snap = snapshot()
        return when {
            snap.state != STATE_PLAYING -> Health.SILENT
            isAd(snap.desc) -> Health.AD
            looseMatch(normalize(title), normalize(snap.desc)) -> Health.OK
            else -> Health.OTHER
        }
    }

    private fun isAd(desc: String): Boolean {
        val d = normalize(desc)
        return d.startsWith("advertisement") || d.startsWith("reklam") || d.startsWith("spotify spotify")
    }

    private fun looseMatch(want: String, seen: String): Boolean {
        if (want.isBlank() || seen.isBlank()) return false
        if (seen.contains(want)) return true
        val words = want.split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return false
        val have = seen.split(' ').toSet()
        return words.count { it in have } * 10 >= words.size * 6
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

    private var spotifyUid: String? = null

    private fun uidOfSpotify(): String? {
        spotifyUid?.let { return it }
        val found = Regex("""uid:(\d+)""").find(su("pm list packages -U $PACKAGE").out)?.groupValues?.get(1)
        spotifyUid = found
        return found
    }

    /** Whether the system really is keeping Spotify's player silent: the line is logged for whoever reads the log. */
    private fun logMuteState() {
        val uid = uidOfSpotify() ?: return
        val line = su("dumpsys audio", 15).out.lines().lastOrNull { it.contains("u/pid:$uid/") } ?: run {
            log("  audio: no player of Spotify's is listed")
            return
        }
        log("  audio player: ${line.trim().take(200)}")
    }

    /** What is worth knowing when Spotify did not play. */
    private fun diagnose() {
        val snap = snapshot()
        log("  media session: state ${snap.state ?: "none"}, \"${snap.desc.take(80)}\"; resumed on display ${spotifyDisplays().ifEmpty { setOf("none") }.joinToString()}")
        val uid = uidOfSpotify()
        if (uid != null) {
            log("  uid $uid state: " + su("cmd activity get-uid-state $uid || am get-uid-state $uid", 8).out.lines().lastOrNull().orEmpty().take(100))
        }
        val audio = su("dumpsys audio", 15).out.lines()
        val focus = audio.indexOfFirst { it.contains("Audio Focus stack", ignoreCase = true) }
        if (focus >= 0) audio.drop(focus).take(3).forEach { log("  ${it.trim().take(140)}") }
    }

    // ── Starting a track: a ladder of ways, each one checked ────────────────

    private enum class Rung(val label: String) { ID("id"), PAGE("page"), SEARCH("search"), VOICE("voice") }

    private enum class Verdict { PLAYING, AD, WRONG, SILENT, CANCELLED }

    private class Outcome(val verdict: Verdict, val detail: String = "")

    private fun amStart(args: String, displayId: Int?): Shell {
        val display = if (displayId != null) "--display $displayId " else ""
        return su("am start $display$args -p $PACKAGE --activity-no-animation", 30)
    }

    /**
     * Waits for the Spotify app to report that it really is playing *this*
     * track. "Playing" with some other title is not a success, and neither is
     * a player that merely says it started.
     */
    private fun confirm(title: String, ticket: Int, displayId: Int?): Outcome {
        val want = normalize(title)
        var wrongRun = 0
        var last: Snap? = null
        for (poll in 1..POLLS) {
            Thread.sleep(POLL_MS)
            if (stale(ticket)) return Outcome(Verdict.CANCELLED)
            // Asked for the hidden screen but it came up on the real one: out of the way at once.
            if (displayId != null && poll <= 3 && "0" in spotifyDisplays()) {
                log("  spotify is on the real screen; sending Back")
                su("input keyevent 4")
            }
            val snap = snapshot()
            last = snap
            if (snap.state != STATE_PLAYING) { wrongRun = 0; continue }
            if (isAd(snap.desc)) {
                log("  spotify is playing an ad (poll $poll)")
                return Outcome(Verdict.AD, snap.desc)
            }
            val seen = normalize(snap.desc)
            if (looseMatch(want, seen)) {
                log("  spotify is playing it: \"${snap.desc.take(70)}\" (poll $poll)")
                return Outcome(Verdict.PLAYING, snap.desc)
            }
            if (seen.isNotBlank() && ++wrongRun >= 3) return Outcome(Verdict.WRONG, snap.desc)
        }
        return Outcome(Verdict.SILENT, "state ${last?.state ?: "no session"}")
    }

    private fun <T : Any> pollUi(displayId: Int?, ticket: Int, find: (List<UiNode>) -> T?): T? {
        val screen = displayId ?: 0
        for (poll in 1..UI_POLLS) {
            Thread.sleep(POLL_MS)
            if (stale(ticket)) return null
            val nodes = dumpUi(screen)
            find(nodes)?.let { return it }
            if (poll == 3 || poll == UI_POLLS) {
                val mine = nodes.count { it.pkg == PACKAGE }
                log("  ui: ${nodes.size} nodes, $mine from Spotify; nothing to touch yet")
                nodes.filter { it.pkg == PACKAGE && it.label.isNotBlank() }.map { it.label.take(36) }.distinct().take(14)
                    .forEach { log("    · $it") }
            }
        }
        return null
    }

    private fun touch(node: UiNode, displayId: Int?, why: String) {
        val x = (node.left + node.right) / 2
        val y = (node.top + node.bottom) / 2
        val target = when {
            node.display != 0 -> "-d ${node.display} "
            displayId != null -> "-d $displayId "
            else -> ""
        }
        val tap = su("input ${target}tap $x $y")
        log("  touched $why \"${node.label.take(40)}\" at $x,$y (code ${tap.code})")
    }

    private fun runRung(rung: Rung, id: String?, title: String, artist: String, displayId: Int?, ticket: Int): Outcome {
        val needsScreen = rung == Rung.PAGE || rung == Rung.SEARCH
        if (needsScreen && displayId == null && !screenOn()) {
            return Outcome(Verdict.SILENT, "the screen is off and there is no hidden screen to read")
        }
        val started = when (rung) {
            Rung.ID -> amStart("-a android.intent.action.VIEW -d spotify:track:$id:play", displayId)
            Rung.PAGE -> amStart("-a android.intent.action.VIEW -d spotify:track:$id", displayId)
            Rung.SEARCH -> {
                val query = URLEncoder.encode("$title $artist".trim(), "UTF-8").replace("+", "%20")
                amStart("-a android.intent.action.VIEW -d ${quoted("spotify:search:$query")}", displayId)
            }
            Rung.VOICE -> amStart(
                "-a android.media.action.MEDIA_PLAY_FROM_SEARCH " +
                    "--es query ${quoted("$title $artist".trim())} " +
                    "--es android.intent.extra.focus vnd.android.cursor.item/audio " +
                    "--es android.intent.extra.title ${quoted(title)} " +
                    "--es android.intent.extra.artist ${quoted(artist)}",
                displayId,
            )
        }
        log("${rung.label} (display ${displayId ?: "real"}): code ${started.code}")
        started.out.lines().filter { it.isNotBlank() }.takeLast(3).forEach { log("  $it") }
        if (started.code < 0) return Outcome(Verdict.SILENT, started.out)
        if (stale(ticket)) return Outcome(Verdict.CANCELLED)
        when (rung) {
            Rung.PAGE -> {
                val button = pollUi(displayId, ticket) { nodes ->
                    nodes.filter {
                        it.pkg == PACKAGE && it.right > it.left && it.bottom > it.top && normalize(it.label) in PLAY_LABELS
                    }.minByOrNull { it.top }
                } ?: return Outcome(if (stale(ticket)) Verdict.CANCELLED else Verdict.SILENT, "no play button on the track page")
                touch(button, displayId, "play")
            }
            Rung.SEARCH -> {
                val row = pollUi(displayId, ticket) { findRow(it, title, artist) }
                    ?: return Outcome(if (stale(ticket)) Verdict.CANCELLED else Verdict.SILENT, "the track was not on the Spotify screen")
                touch(row, displayId, "result")
            }
            else -> Unit
        }
        return confirm(title, ticket, displayId)
    }

    /**
     * Gets Spotify to play this track, trying the ways in turn until one is
     * confirmed: the one that worked last time first. Null on success, or what
     * went wrong. Quits quietly as soon as a newer attempt (or a stop) exists.
     */
    fun startPlayback(id: String?, title: String, artist: String, displayId: Int?, ticket: Int): String? {
        val order = mutableListOf<Rung>()
        if (id != null) { order += Rung.ID; order += Rung.PAGE }
        order += Rung.SEARCH
        order += Rung.VOICE
        val good = Rung.values().firstOrNull { it.label == lastGoodRung }
        if (good != null && good in order) { order.remove(good); order.add(0, good) }
        var problem = "none of the ways of starting it worked"
        var first = true
        for (rung in order) {
            if (stale(ticket)) return "cancelled"
            if (!first) forceStop()
            first = false
            val outcome = runRung(rung, id, title, artist, displayId, ticket)
            when (outcome.verdict) {
                Verdict.PLAYING, Verdict.AD -> {
                    lastGoodRung = rung.label
                    logMuteState()
                    if (displayId == null) leaveSpotifyOnRealScreen()
                    return null
                }
                Verdict.CANCELLED -> return "cancelled"
                Verdict.WRONG -> {
                    problem = "Spotify played \"${outcome.detail.take(50)}\" instead"
                    log("  ${rung.label}: $problem")
                }
                Verdict.SILENT -> {
                    problem = "Spotify did not play it (${outcome.detail.take(60)})"
                    log("  ${rung.label}: $problem")
                }
            }
        }
        diagnose()
        return problem
    }

    /** Spotify was only on the real screen to get going: Back, until it is no longer the app in front. */
    private fun leaveSpotifyOnRealScreen() {
        repeat(3) {
            Thread.sleep(800)
            if ("0" !in spotifyDisplays()) return
            su("input keyevent 4")
            log("  sent Back")
        }
    }

    // ── Driving the Spotify app's own search, by touch ──────────────────────

    private const val UI_DUMP = "/data/local/tmp/seamless-ui.xml"
    private const val UI_POLLS = 8

    private class UiNode(
        val pkg: String,
        val cls: String,
        val label: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val display: Int,
    )

    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    private fun parseUi(xml: String): List<UiNode> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val nodes = mutableListOf<UiNode>()
        var display = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display" -> display = parser.getAttributeValue(null, "id")?.toIntOrNull() ?: 0
                    "node" -> {
                        val b = boundsPattern.find(parser.getAttributeValue(null, "bounds").orEmpty())
                            ?.groupValues?.drop(1)?.map { it.toInt() }
                        if (b != null) {
                            val text = parser.getAttributeValue(null, "text").orEmpty()
                            val desc = parser.getAttributeValue(null, "content-desc").orEmpty()
                            nodes += UiNode(
                                pkg = parser.getAttributeValue(null, "package").orEmpty(),
                                cls = parser.getAttributeValue(null, "class").orEmpty(),
                                label = text.ifBlank { desc },
                                left = b[0], top = b[1], right = b[2], bottom = b[3],
                                clickable = parser.getAttributeValue(null, "clickable") == "true",
                                display = display,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return nodes
    }

    /** The Spotify screen as text. Newer systems take a display number; older ones only see the real screen. */
    private fun dumpUi(displayId: Int): List<UiNode> {
        val flag = if (displayId != 0) "--display $displayId " else ""
        val out = su("rm -f $UI_DUMP; uiautomator dump $flag$UI_DUMP >/dev/null 2>&1; cat $UI_DUMP 2>/dev/null", 25).out
        if (!out.contains("<hierarchy")) return emptyList()
        return runCatching { parseUi(out) }.getOrElse {
            log("  ui: could not read the dump (${it.javaClass.simpleName})")
            emptyList()
        }
    }

    /** The text on the search results that is this track's title, with its artist somewhere in the same row. */
    private fun findRow(nodes: List<UiNode>, title: String, artist: String): UiNode? {
        val wantTitle = normalize(title)
        if (wantTitle.isBlank()) return null
        val wantArtists = artistsOf(artist)
        val mine = nodes.filter { it.pkg == PACKAGE && it.right > it.left && it.bottom > it.top }
        var best: UiNode? = null
        var bestScore = 0
        for (n in mine) {
            // The search box repeats the query; it is not a result.
            if (n.cls.contains("EditText")) continue
            val label = normalize(n.label)
            val score = when {
                label.isBlank() -> 0
                label == wantTitle -> 3
                label.contains(wantTitle) -> 1
                else -> 0
            }
            if (score == 0) continue
            val row = mine
                .filter {
                    it.clickable && it.display == n.display && it.left <= n.left && it.top <= n.top &&
                        it.right >= n.right && it.bottom >= n.bottom
                }
                .minByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) } ?: n
            val around = mine
                .filter {
                    it.display == n.display && it.left >= row.left && it.top >= row.top &&
                        it.right <= row.right && it.bottom <= row.bottom
                }
                .joinToString(" ") { normalize(it.label) }
            if (wantArtists.isNotEmpty() && wantArtists.none { around.contains(it) }) continue
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }

    // ── Keeping it clean ────────────────────────────────────────────────────

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
                  $RESTORE_SOUND
                  rm -f $WATCHDOG_PID
                  exit 0
                fi
              done
            ' >/dev/null 2>&1 </dev/null &
        """.trimIndent()
        val result = su(script, 10)
        log("watchdog: code ${result.code}")
    }

    /** Stops the Spotify app without touching its sound settings: for starting over. */
    fun forceStop() {
        su("am force-stop $PACKAGE")
    }

    /** Stops Spotify, puts its audio settings back and retires the watchdog. */
    fun stop() {
        cancelAttempts()
        val result = su(
            "[ -f $WATCHDOG_PID ] && kill \$(cat $WATCHDOG_PID) 2>/dev/null; rm -f $WATCHDOG_PID; " +
                "am force-stop $PACKAGE; $RESTORE_SOUND",
        )
        if (result.code == 0) dirty = false
        log("stop: code ${result.code}")
    }

    /** Only puts the audio settings back, for the times Spotify is used by hand or was left muted. */
    fun restoreSound() {
        cancelAttempts()
        val result = su("[ -f $WATCHDOG_PID ] && kill \$(cat $WATCHDOG_PID) 2>/dev/null; rm -f $WATCHDOG_PID; $RESTORE_SOUND")
        if (result.code == 0) dirty = false
        log("restore sound: code ${result.code}")
    }

    // ── A report of what this phone allows ──────────────────────────────────

    /** Writes what this phone does and does not allow into the log, one line each. For the self-test button. */
    fun selfTest(hiddenScreen: String) {
        fun show(name: String, script: String, lines: Int = 3) {
            val r = su(script, 20)
            val body = r.out.lines().filter { it.isNotBlank() }.take(lines).joinToString(" | ").take(220)
            log("test $name: ${if (r.code == 0) "" else "(code ${r.code}) "}$body")
        }
        log("── self-test ──")
        show("root", "id")
        show("device", "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.display.id", 4)
        show("spotify", "dumpsys package $PACKAGE | grep -E 'versionName|targetSdk' | head -2", 2)
        show("appops", "cmd appops get $PACKAGE PLAY_AUDIO; cmd appops get $PACKAGE TAKE_AUDIO_FOCUS", 2)
        show("doze", "cmd deviceidle whitelist | grep -E '$PACKAGE|$ownPackage'", 2)
        show("uiautomator --display", "uiautomator dump --display 0 /data/local/tmp/seamless-t.xml 2>&1 | head -2; rm -f /data/local/tmp/seamless-t.xml", 2)
        show("boot script", "ls -l $BOOT_SCRIPT 2>&1")
        log("test screen: ${if (screenOn()) "on" else "off"}; hidden screen: $hiddenScreen")
        val snap = snapshot()
        log("test spotify session: state ${snap.state ?: "none"}, \"${snap.desc.take(80)}\"")
        log("── end of self-test ──")
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
