package com.music.bitchord.presence

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Spotify activity's own log: what it did and what the web page answered, newest last. */
internal object PresenceLog {
    private const val MAX_LINES = 500

    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(line: String) {
        synchronized(lock) {
            lines.addLast("${clock.format(Date())}  $line")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun text(): String = synchronized(lock) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }
}
