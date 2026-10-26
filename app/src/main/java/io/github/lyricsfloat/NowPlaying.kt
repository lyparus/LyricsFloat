package io.github.lyricsfloat

import android.graphics.Bitmap

/**
 * Что сейчас играет и что про это известно. Общее состояние для плавающего окна
 * и полноэкранного режима; заполняется из [MediaListener]. Только главный поток.
 */
object NowPlaying {
    enum class Change { TRACK, LYRICS, ART, OFFSET, FULLSCREEN, PLAYBACK, AUTO_SCROLL }

    /** Управление плеером, который сейчас играет. */
    interface Controls {
        fun playPause()
        fun next()
        fun previous()
        fun seekTo(ms: Long)
    }

    var isPlaying = false; private set
    /** Длительность трека по данным плеера; 0 — неизвестна. */
    var durationMs = 0L; private set
    var controls: Controls? = null

    fun setPlayback(playing: Boolean, duration: Long) {
        if (playing == isPlaying && duration == durationMs) return
        isPlaying = playing
        durationMs = duration
        emit(Change.PLAYBACK)
    }

    var hasTrack = false; private set
    var artist = ""; private set
    var title = ""; private set
    var art: Bitmap? = null; private set
    /** null — текст ещё ищется, а что происходит, описано в [status]. */
    var lyrics: Lyrics? = null; private set
    var status = ""; private set
    /** Сдвиг текста относительно трека; положительный — строки загораются раньше. */
    var offsetMs = 0L; private set
    var fullscreenOpen = false; private set
    /** Плавно прокручивать текст без таймкодов в такт прогрессу трека. */
    var autoScroll = false; private set

    var positionProvider: () -> Long? = { null }
    var retry: () -> Unit = {}

    private val listeners = mutableSetOf<(Change) -> Unit>()

    fun addListener(l: (Change) -> Unit) { listeners += l }
    fun removeListener(l: (Change) -> Unit) { listeners -= l }
    private fun emit(c: Change) = listeners.toList().forEach { it(c) }

    fun position(): Long? = positionProvider()

    fun setTrack(artist: String, title: String) {
        hasTrack = true
        this.artist = artist
        this.title = title
        lyrics = null
        status = "Ищу текст…"
        offsetMs = 0
        emit(Change.TRACK)
    }

    fun clearTrack() {
        if (!hasTrack) return
        hasTrack = false
        art = null
        lyrics = null
        emit(Change.TRACK)
    }

    fun setStatus(text: String) {
        lyrics = null
        status = text
        emit(Change.LYRICS)
    }

    fun setLyrics(l: Lyrics) {
        lyrics = l
        emit(Change.LYRICS)
    }

    fun setArt(bitmap: Bitmap?) {
        if (bitmap === art) return
        art = bitmap
        emit(Change.ART)
    }

    fun setOffset(ms: Long) {
        offsetMs = ms.coerceIn(-30_000, 30_000)
        emit(Change.OFFSET)
    }

    fun setAutoScroll(on: Boolean) {
        if (on == autoScroll) return
        autoScroll = on
        emit(Change.AUTO_SCROLL)
    }

    fun setFullscreen(open: Boolean) {
        if (open == fullscreenOpen) return
        fullscreenOpen = open
        emit(Change.FULLSCREEN)
    }
}
