package io.github.lyricsfloat

/** Поддерживаемые приложения-источники. */
enum class Source(val prefKey: String, val label: String, val defaultOn: Boolean, val packages: Set<String>) {
    YANDEX("src_yandex", "Яндекс Музыка", true, setOf("ru.yandex.music")),
    YT_MUSIC(
        "src_yt_music", "YouTube Music", true,
        setOf(
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "app.rvx.android.apps.youtube.music",
        ),
    ),
    YOUTUBE(
        "src_youtube", "YouTube", true,
        setOf(
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "app.rvx.android.youtube",
        ),
    ),
    ANY("src_any", "Любой другой плеер", false, emptySet());

    companion object {
        fun of(pkg: String) = entries.firstOrNull { pkg in it.packages } ?: ANY
    }
}

data class Track(val artist: String, val title: String, val album: String?, val durationMs: Long)

/** Приводит метаданные к виду «исполнитель + название песни», пригодному для поиска текста. */
object TrackNormalizer {

    fun normalize(source: Source, artist: String, title: String, album: String?, durationMs: Long): Track =
        when (source) {
            Source.YOUTUBE -> fromVideo(artist, title)
            Source.YT_MUSIC -> Track(cleanChannel(artist), title, album, durationMs)
            else -> Track(artist, title, album, durationMs)
        }

    /**
     * В YouTube артистом приходит имя канала, а название — «Artist - Song (Official Video)».
     * Длительность клипа часто не совпадает с треком, поэтому её не передаём.
     */
    private fun fromVideo(channel: String, rawTitle: String): Track {
        var t = rawTitle
            .replace(Regex("""\s*[(\[【][^)\]】]*[)\]】]"""), "")   // (Official Video), [4K], 【MV】
            .replace(Regex("""\s*[|｜].*$"""), "")                 // «… | Премьера клипа»
            .replace(Regex("""\s+(official\s+)?(music\s+)?(video|audio|lyrics?|visualizer|клип)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        t = t.trim('"', '«', '»', ' ')

        val dash = Regex("""\s+[-–—]\s+""").find(t)
        return if (dash != null) {
            val artist = t.substring(0, dash.range.first).trim()
            val song = t.substring(dash.range.last + 1).trim().trim('"', '«', '»', ' ')
            Track(artist, song.ifEmpty { t }, null, 0)
        } else {
            Track(cleanChannel(channel), t.ifEmpty { rawTitle }, null, 0)
        }
    }

    private fun cleanChannel(channel: String) = channel
        .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""VEVO$"""), "")
        .replace(Regex("""\s+(Official|Официальный канал)$""", RegexOption.IGNORE_CASE), "")
        .trim()
        .ifEmpty { channel }
}
