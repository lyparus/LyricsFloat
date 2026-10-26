package io.github.lyricsfloat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Офлайн-кэш найденных текстов: по файлу JSON на трек в filesDir/lyrics_cache, не больше MAX_ENTRIES. */
object LyricsCache {
    private const val MAX_ENTRIES = 500
    private var dir: File? = null

    fun init(ctx: Context) {
        dir = File(ctx.applicationContext.filesDir, "lyrics_cache")
        dir?.mkdirs()
    }

    @Synchronized
    fun get(artist: String, title: String): Lyrics? {
        if (dir == null) return null
        try {
            val file = fileFor(artist, title)
            if (!file.exists()) return null
            
            val jsonText = file.readText(Charsets.UTF_8)
            val obj = JSONObject(jsonText)
            
            file.setLastModified(System.currentTimeMillis())
            
            return when (obj.getString("type")) {
                "synced" -> {
                    val lines = obj.getJSONArray("lines")
                    val lyricLines = mutableListOf<LyricLine>()
                    for (i in 0 until lines.length()) {
                        val line = lines.getJSONObject(i)
                        lyricLines.add(LyricLine(line.getLong("t"), line.getString("x")))
                    }
                    Lyrics.Synced(lyricLines, obj.optString("source", "LRCLIB"))
                }
                "plain" -> Lyrics.Plain(obj.getString("text"), obj.optString("source", "LRCLIB"))
                "instrumental" -> Lyrics.Instrumental
                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }

    @Synchronized
    fun put(artist: String, title: String, lyrics: Lyrics) {
        if (dir == null) return
        try {
            when (lyrics) {
                is Lyrics.Synced -> {
                    val file = fileFor(artist, title)
                    
                    val obj = JSONObject()
                    obj.put("type", "synced")
                    obj.put("source", lyrics.source)
                    obj.put("savedAt", System.currentTimeMillis())
                    
                    val linesArray = JSONArray()
                    for (line in lyrics.lines) {
                        val lineObj = JSONObject()
                        lineObj.put("t", line.timeMs)
                        lineObj.put("x", line.text)
                        linesArray.put(lineObj)
                    }
                    obj.put("lines", linesArray)
                    
                    file.writeText(obj.toString(), Charsets.UTF_8)
                }
                is Lyrics.Plain -> {
                    val file = fileFor(artist, title)
                    
                    val obj = JSONObject()
                    obj.put("type", "plain")
                    obj.put("source", lyrics.source)
                    obj.put("savedAt", System.currentTimeMillis())
                    obj.put("text", lyrics.text)
                    
                    file.writeText(obj.toString(), Charsets.UTF_8)
                }
                is Lyrics.Instrumental -> {
                    val file = fileFor(artist, title)
                    
                    val obj = JSONObject()
                    obj.put("type", "instrumental")
                    obj.put("savedAt", System.currentTimeMillis())
                    
                    file.writeText(obj.toString(), Charsets.UTF_8)
                }
                else -> return
            }
            
            // Не даём кэшу расти бесконечно.
            cleanupOldEntries()
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        if (dir == null) return
        try {
            dir?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun count(): Int {
        if (dir == null) return 0
        try {
            return dir?.listFiles()?.size ?: 0
        } catch (e: Exception) {
            return 0
        }
    }

    @Synchronized
    fun sizeBytes(): Long {
        if (dir == null) return 0L
        try {
            return dir?.listFiles()?.map { it.length() }?.sumOf { it } ?: 0L
        } catch (e: Exception) {
            return 0L
        }
    }

    /** Файл кэша для трека: SHA-1 от «исполнитель|название» в нижнем регистре. */
    private fun fileFor(artist: String, title: String): File {
        val key = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    private fun cleanupOldEntries() {
        if (dir == null) return
        try {
            val files = dir?.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size <= MAX_ENTRIES) return
            
            for (i in 0 until files.size - MAX_ENTRIES) {
                files[i].delete()
            }
        } catch (e: Exception) {
        }
    }
}
