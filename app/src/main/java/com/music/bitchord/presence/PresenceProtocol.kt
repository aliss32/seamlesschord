package com.music.bitchord.presence

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/** What the presence process reports about itself. */
enum class PresenceState { OFF, STARTING, SIGN_IN_NEEDED, SEARCHING, PLAYING, PAUSED, NOT_FOUND, ERROR }

/**
 * The messages between the app and the presence process — see
 * [SpotifyPresenceService]. Plain ints and strings in a Bundle, so nothing
 * of this app has to exist in the other process to read them.
 */
internal object PresenceProtocol {
    const val MSG_CONFIGURE = 1
    const val MSG_TRACK = 2
    const val MSG_PAUSE = 3
    const val MSG_STATUS = 100

    const val KEY_SPDC = "spdc"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_DURATION_SEC = "duration_sec"
    const val KEY_STATE = "state"
    const val KEY_DETAIL = "detail"

    const val PROCESS_SUFFIX = ":spotify"

    /**
     * Whether this is the process that hosts the Spotify web player and
     * nothing else. Everything [BitChordApplication] sets up for the player —
     * the audio cache, sources, the session — belongs to the main process
     * alone, and a second copy of it fighting over the same files is the one
     * way this feature could hurt playback.
     */
    fun isPresenceProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000') }.getOrDefault("")
        }
        return name.orEmpty().endsWith(PROCESS_SUFFIX)
    }
}
