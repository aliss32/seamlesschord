package com.music.bitchord.presence

import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Finding the Spotify track that matches what BitChord is playing: first by
 * Spotify's search API (exact, and checks the length), and, when that is
 * unavailable, from the rows of the web player's own search page.
 */
internal object SpotifyLookup {

    private const val DURATION_TOLERANCE_SEC = 8

    class Lookup(val id: String?, val error: String? = null, val limited: Boolean = false)

    /** A row of the web player's search results. */
    class WebRow(val id: String, val title: String, val artists: List<String>, val seconds: Int)

    /** Until when the search API is left alone, after it said it was being asked too much. */
    @Volatile
    private var apiBlockedUntil = 0L

    /** Tracks already matched, so skipping back and forth does not ask Spotify twice. */
    private val matched = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    fun remember(title: String, artist: String, durationSec: Int?, id: String) {
        synchronized(matched) { matched[keyOf(title, artist, durationSec)] = id }
    }

    private fun keyOf(title: String, artist: String, durationSec: Int?) =
        "${normalize(title)}|${normalize(artist)}|${durationSec ?: 0}"

    suspend fun findTrack(title: String, artist: String, durationSec: Int?): Lookup {
        val key = keyOf(title, artist, durationSec)
        synchronized(matched) { matched[key] }?.let {
            PresenceLog.log("lookup: remembered match $it")
            return Lookup(it)
        }
        if (System.currentTimeMillis() < apiBlockedUntil) {
            return Lookup(null, "the search API is resting", limited = true)
        }
        var token = withContext(Dispatchers.IO) { SpotifyToken.accessToken() }
        if (token.isNullOrBlank()) return Lookup(null, "no access token", limited = true)
        val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        var refreshed = false
        while (true) {
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
                        PresenceLog.log("lookup: HTTP 429, Retry-After ${wait}s; using the web search instead")
                    }
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    code to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
            }.getOrElse { return Lookup(null, "the search failed: ${it.message}", limited = true) }
            val (code, body) = reply
            if (code == 429) return Lookup(null, "the search API is rate limited", limited = true)
            if (code == 401 && !refreshed) {
                refreshed = true
                SpotifyToken.invalidate()
                token = withContext(Dispatchers.IO) { SpotifyToken.accessToken() }
                if (token.isNullOrBlank()) return Lookup(null, "no access token", limited = true)
                continue
            }
            if (code !in 200..299) {
                PresenceLog.log("lookup: HTTP $code ${body.take(120)}")
                return Lookup(null, "the search failed (HTTP $code)", limited = true)
            }
            val items = runCatching { JSONObject(body).optJSONObject("tracks")?.optJSONArray("items") }.getOrNull()
                ?: JSONArray()
            val id = bestMatch(title, artist, durationSec, items)
            PresenceLog.log("lookup: ${items.length()} results from the API, match ${id ?: "none"}")
            if (id != null) remember(title, artist, durationSec, id)
            return Lookup(id)
        }
    }

    private fun bestMatch(title: String, artist: String, durationSec: Int?, items: JSONArray): String? {
        val rows = (0 until items.length()).mapNotNull { k ->
            val item = items.optJSONObject(k) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val credited = item.optJSONArray("artists")
            val names = (0 until (credited?.length() ?: 0)).mapNotNull { credited?.optJSONObject(it)?.optString("name") }
            WebRow(id, item.optString("name"), names, item.optInt("duration_ms", -1) / 1000)
        }
        return bestRow(title, artist, durationSec, rows)
    }

    /** The best of the rows the web player's search page showed, or null when none is the track. */
    fun bestWebMatch(title: String, artist: String, durationSec: Int?, rows: List<WebRow>): String? =
        bestRow(title, artist, durationSec, rows)

    private fun bestRow(title: String, artist: String, durationSec: Int?, rows: List<WebRow>): String? {
        val wantTitle = normalize(title)
        val wantArtists = artistsOf(artist)
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (row in rows) {
            val got = normalize(row.title)
            if (got.isBlank() || wantTitle.isBlank()) continue
            val titleScore = when {
                got == wantTitle -> 3
                got.contains(wantTitle) || wantTitle.contains(got) -> 1
                else -> 0
            }
            if (titleScore == 0) continue
            val gotArtists = row.artists.map { normalize(it) }.filter { it.isNotBlank() }
            val artistOk = wantArtists.isEmpty() || gotArtists.isEmpty() ||
                wantArtists.any { a -> gotArtists.any { g -> g == a || g.contains(a) || a.contains(g) } }
            if (!artistOk) continue
            var score = titleScore * 100
            if (row.seconds > 0 && durationSec != null) {
                val gap = abs(row.seconds - durationSec)
                if (gap > DURATION_TOLERANCE_SEC) continue
                score -= gap
            }
            if (score > bestScore) {
                bestScore = score
                best = row.id
            }
        }
        return best
    }

    private fun artistsOf(credit: String): List<String> =
        credit.lowercase()
            .split(Regex("""\s*(?:,|;|/|&|\bfeat\.?|\bft\.?|\bx\b|\band\b|\bve\b)\s*"""))
            .map { normalize(it) }
            .filter { it.isNotBlank() }

    fun normalize(text: String?): String =
        text.orEmpty().lowercase()
            .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    /** Whether [seen] (already normalized) is the track whose normalized title is [want], allowing extra words. */
    fun looseMatch(want: String, seen: String): Boolean {
        if (want.isBlank() || seen.isBlank()) return false
        if (seen.contains(want)) return true
        val words = want.split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return false
        val have = seen.split(' ').toSet()
        return words.count { it in have } * 10 >= words.size * 6
    }

    /** Spotify's own advertisements, in the app languages this is likely to run in. */
    fun isAd(shown: String): Boolean {
        val d = normalize(shown)
        return d.contains("advertisement") || d.startsWith("reklam") || d.contains(" reklam ")
    }
}
