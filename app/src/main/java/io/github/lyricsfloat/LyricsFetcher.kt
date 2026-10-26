package io.github.lyricsfloat

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

sealed class Lyrics {
    data class Synced(val lines: List<LyricLine>, val source: String = "LRCLIB") : Lyrics()
    data class Plain(val text: String, val source: String = "LRCLIB") : Lyrics()
    object Instrumental : Lyrics()
    object NotFound : Lyrics()
    data class Error(val message: String) : Lyrics()
}

class HttpException(val code: Int, val retryAfterMs: Long?) : IOException(
    when (code) {
        503 -> "сервер LRCLIB перегружен (503)"
        429 -> "слишком много запросов к LRCLIB (429)"
        else -> "LRCLIB ответил HTTP $code"
    }
) {
    val retryable get() = code == 429 || code in 500..599
}

/**
 * Ищет тексты: сначала в открытой базе LRCLIB (https://lrclib.net),
 * затем запасным источником — NetEase Cloud Music (неофициальное API).
 */
object LyricsFetcher {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "LyricsFloat/1.0 (Android)"

    /** Паузы между повторами, когда LRCLIB отвечает 503/429. */
    private val RETRY_DELAYS = longArrayOf(1500, 3000, 6000)

    private val cache = object : LinkedHashMap<String, Lyrics>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>) = size > 50
    }

    fun fetch(
        artist: String,
        title: String,
        album: String?,
        durationMs: Long,
        onStatus: (String) -> Unit = {},
    ): Lyrics {
        val key = "$artist|$title"
        synchronized(cache) { cache[key]?.let { return it } }
        // Офлайн-кэш на телефоне: мгновенно и без интернета.
        LyricsCache.get(artist, title)?.let { saved ->
            synchronized(cache) { cache[key] = saved }
            return saved
        }
        val result = try {
            find(artist, title, album, durationMs, onStatus)
        } catch (e: Exception) {
            // Ошибки сети не кэшируем, чтобы можно было повторить.
            return Lyrics.Error(e.message ?: e.javaClass.simpleName)
        }
        // «Не найдено» на диск не пишем: текст может появиться в базе позже.
        LyricsCache.put(artist, title, result)
        synchronized(cache) { cache[key] = result }
        return result
    }

    private fun find(artist: String, title: String, album: String?, rawDurationMs: Long, onStatus: (String) -> Unit): Lyrics {
        val durationMs = if (isSpeedChanged(title)) 0L else rawDurationMs
        var lastError: IOException? = null
        val lrclib = try {
            findLrclib(artist, title, album, durationMs, onStatus)
        } catch (e: IOException) {
            lastError = e
            null
        }
        if (lrclib is Lyrics.Synced || lrclib is Lyrics.Instrumental) return lrclib

        // LRCLIB не нашёл или нашёл только текст без таймкодов — пробуем NetEase.
        val netease = try {
            NetEase.find(mainArtist(artist), cleanTitle(title), durationMs / 1000)
        } catch (e: Exception) {
            if (lastError == null) lastError = e as? IOException ?: IOException("NetEase: ${e.message}")
            null
        }
        if (netease is Lyrics.Synced) return netease

        lrclib?.let { return it }
        netease?.let { return it }
        // Если что-то упало по сети — это не «не найдено», пусть можно будет повторить.
        lastError?.let { throw it }
        return Lyrics.NotFound
    }

    /** null — не найдено; исключение — была сетевая ошибка, а найти ничего не удалось. */
    private fun findLrclib(artist: String, title: String, album: String?, durationMs: Long, onStatus: (String) -> Unit): Lyrics? {
        val sec = durationMs / 1000
        var lastError: IOException? = null

        val exact = mutableListOf("artist_name" to artist, "track_name" to title)
        if (!album.isNullOrBlank()) exact += "album_name" to album
        if (sec > 0) exact += "duration" to sec.toString()

        // 1. Только локальная база LRCLIB — быстро и почти никогда не перегружена.
        // 2. Полный поиск по точному совпадению (сервер может опрашивать внешние источники).
        for (path in listOf("/get-cached", "/get")) {
            try {
                get(path, exact, onStatus)?.let { body -> parse(JSONObject(body))?.let { return it } }
            } catch (e: IOException) {
                lastError = e
            }
        }

        // 3. Поиск по очищенному названию: без "(feat. ...)", "sped up", "гр." и соавторов.
        val cleanTitle = cleanTitle(title)
        val mainArtist = mainArtist(artist)
        if (cleanTitle != title || mainArtist != artist) {
            val cleanExact = mutableListOf("artist_name" to mainArtist, "track_name" to cleanTitle)
            if (sec > 0) cleanExact += "duration" to sec.toString()
            try {
                get("/get-cached", cleanExact, onStatus)?.let { body -> parse(JSONObject(body))?.let { return it } }
            } catch (e: IOException) {
                lastError = e
            }
        }
        val queries = listOf(
            listOf("track_name" to cleanTitle, "artist_name" to mainArtist),
            listOf("q" to "$mainArtist $cleanTitle"),
        )
        for (q in queries) {
            try {
                val body = get("/search", q, onStatus) ?: continue
                pickBest(JSONArray(body), sec)?.let { obj -> parse(obj)?.let { return it } }
            } catch (e: IOException) {
                lastError = e
            }
        }

        lastError?.let { throw it }
        return null
    }

    /** Из результатов поиска выбирает близкий по длительности трек, предпочитая синхронизированный текст. */
    private fun pickBest(arr: JSONArray, sec: Long): JSONObject? {
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { sec <= 0 || abs(it.optDouble("duration", 0.0) - sec) <= 5 }
            .filter { it.optBoolean("instrumental") || hasText(it, "syncedLyrics") || hasText(it, "plainLyrics") }
        return items.sortedWith(
            compareBy<JSONObject>({ if (hasText(it, "syncedLyrics")) 0 else 1 },
                { if (sec > 0) abs(it.optDouble("duration", 0.0) - sec) else 0.0 })
        ).firstOrNull()
    }

    private fun parse(obj: JSONObject): Lyrics? {
        if (hasText(obj, "syncedLyrics")) {
            val lines = parseLrc(obj.getString("syncedLyrics"))
            if (lines.isNotEmpty()) return Lyrics.Synced(lines)
        }
        if (hasText(obj, "plainLyrics")) return Lyrics.Plain(obj.getString("plainLyrics").trim())
        if (obj.optBoolean("instrumental")) return Lyrics.Instrumental
        return null
    }

    private val timeTag = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")

    fun parseLrc(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (raw in lrc.lineSequence()) {
            val tags = timeTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim().ifEmpty { "♪" }
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val s = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = if (frac.isEmpty()) 0L else (frac + "00").take(3).toLong()
                result += LyricLine(min * 60_000 + s * 1000 + ms, text)
            }
        }
        return result.sortedBy { it.timeMs }
    }

    private fun hasText(obj: JSONObject, key: String) =
        !obj.isNull(key) && obj.optString(key).isNotBlank()

    /** Приписки перезаливов: ускоренные/замедленные версии, ремиксы, «lyrics», «official video» и т. п. */
    private const val TAGS = """sped\s*up|speed\s*up|slowed(\s*(\+|and|&)\s*reverb(ed)?)?|reverb(ed)?|nightcore|""" +
        """8d(\s*audio)?|bass\s*boost(ed)?|remix|mix|edit|radio\s*edit|extended(\s*mix)?|remaster(ed)?(\s*\d{4})?|""" +
        """lyrics?(\s*video)?|official(\s*(music|lyric))?(\s*(video|audio|clip))?|music\s*video|audio|video|""" +
        """visuali[sz]er|hd|hq|4k|tik\s*tok(\s*version)?|clean|explicit|instrumental|karaoke|cover|""" +
        """prod\.?\s*(by)?\s*.*|премьера(\s*клипа)?|клип|текст(\s*песни)?|ускоренн\w*|замедленн\w*|ремикс"""

    /** Версия с изменённой скоростью: длительность не совпадёт с оригиналом. */
    private val speedTag = Regex("""sped\s*up|speed\s*up|slowed|nightcore|ускоренн|замедленн""", RegexOption.IGNORE_CASE)

    internal fun isSpeedChanged(title: String) = speedTag.containsMatchIn(title)

    internal fun cleanTitle(title: String): String {
        val cleaned = title
            .replace(Regex("""\s*[(\[【][^)\]】]*[)\]】]"""), "")                     // (Official Video), [sped up]
            .replace(Regex("""^\s*\d{1,3}\s*[.)\-–]\s+"""), "")                          // «01. Название»
            .replace(Regex("""\s+(feat\.?|ft\.?|при уч\.?)\s.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[|｜/].*$"""), "")                                       // «… | Премьера»
            // «Название - sped up», «Название slowed + reverb» — приписка в конце, даже без скобок.
            .replace(Regex("""(\s*[-–—:]\s*|\s+)($TAGS)(\s*[-–—+&,]?\s*($TAGS))*\s*$""", RegexOption.IGNORE_CASE), "")
            .trim().trim('-', '–', '—', ':', '"', '«', '»', ' ')
        return cleaned.ifEmpty { title }
    }

    internal fun mainArtist(artist: String): String {
        val first = artist.split(Regex("""\s*(,|&|;|\s(feat\.?|ft\.?|x|и|при уч\.?)\s)\s*""", RegexOption.IGNORE_CASE))
            .first()
        val cleaned = first
            .replace(Regex("""^\s*(гр\.|группа|group|band)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""VEVO$"""), "")
            .replace(Regex("""\s+(official|music|официальный канал)$""", RegexOption.IGNORE_CASE), "")
            .trim().trim('"', '«', '»')
        return cleaned.ifEmpty { artist.trim() }
    }

    /** GET с повторами при перегрузке сервера; null при 404. */
    private fun get(path: String, params: List<Pair<String, String>>, onStatus: (String) -> Unit): String? {
        var attempt = 0
        while (true) {
            try {
                return getOnce(path, params)
            } catch (e: IOException) {
                val retryable = (e is HttpException && e.retryable) || e is SocketTimeoutException
                if (!retryable || attempt >= RETRY_DELAYS.size) throw e
                val wait = (e as? HttpException)?.retryAfterMs?.coerceIn(500, 10_000) ?: RETRY_DELAYS[attempt]
                attempt++
                onStatus("${e.message}\nПовтор $attempt из ${RETRY_DELAYS.size}…")
                Thread.sleep(wait)
            }
        }
    }

    private fun getOnce(path: String, params: List<Pair<String, String>>): String? {
        val query = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                404 -> null
                else -> {
                    val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                    throw HttpException(code, retryAfter)
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
