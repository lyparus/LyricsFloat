package io.github.lyricsfloat

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Запасной источник текстов — NetEase Cloud Music. API неофициальное:
 * много синхронизированных текстов к западным трекам, русских — мало.
 */
object NetEase {
    private const val SEARCH = "https://music.163.com/api/cloudsearch/pc"
    private const val LYRIC = "https://music.163.com/api/song/lyric"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private const val MAX_CANDIDATES = 3

    /** Строки с авторами в начале текста: «作词 : …», «Composer: …». */
    private val creditLine = Regex(
        """^\s*(作词|作曲|编曲|制作人?|监制|混音|母带|和声|吉他|贝斯|鼓|录音|出品|演唱|原唱|OP|SP|""" +
            """Lyrics?|Lyricist|Composer|Arranger|Producer|Written by|Music by)[^:：]{0,20}[:：]""",
        RegexOption.IGNORE_CASE,
    )

    fun find(artist: String, title: String, sec: Long): Lyrics? {
        val body = get(SEARCH, listOf("s" to "$artist $title", "type" to "1", "limit" to "10")) ?: return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null

        val wantTitle = norm(title)
        val wantArtist = norm(artist)
        val candidates = (0 until songs.length()).map { songs.getJSONObject(it) }
            .map { song ->
                val name = norm(song.optString("name"))
                val artists = song.optJSONArray("ar")
                val names = (0 until (artists?.length() ?: 0)).map { norm(artists!!.getJSONObject(it).optString("name")) }
                val diff = if (sec > 0) abs(song.optLong("dt") / 1000 - sec) else 0
                val titleOk = name.isNotEmpty() && (name.contains(wantTitle) || wantTitle.contains(name))
                val artistOk = names.any { it.isNotEmpty() && (it.contains(wantArtist) || wantArtist.contains(it)) }
                Triple(song, titleOk && (artistOk || diff <= 2) && (sec <= 0 || diff <= 5), diff)
            }
            .filter { it.second }
            .sortedBy { it.third }
            .take(MAX_CANDIDATES)

        for ((song, _, _) in candidates) {
            val lyricBody = get(LYRIC, listOf("id" to song.optLong("id").toString(), "lv" to "1", "tv" to "-1")) ?: continue
            val json = JSONObject(lyricBody)
            if (json.optBoolean("pureMusic")) return Lyrics.Instrumental
            val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
            if (lrc.isBlank()) continue
            val lines = LyricsFetcher.parseLrc(lrc).filterNot { creditLine.containsMatchIn(it.text) }
            if (lines.size >= 3) return Lyrics.Synced(lines, source = "NetEase")
            // Текст без таймкодов.
            val plain = lrc.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("{") && !creditLine.containsMatchIn(it) }
                .joinToString("\n")
            if (plain.lines().size >= 3) return Lyrics.Plain(plain, source = "NetEase")
        }
        return null
    }

    /** Регистр, пробелы и знаки препинания не важны. */
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun get(url: String, params: List<Pair<String, String>>): String? {
        val query = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL("$url?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://music.163.com")
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                404 -> null
                else -> throw IOException("NetEase ответил HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
