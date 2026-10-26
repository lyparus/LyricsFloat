package io.github.lyricsfloat

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Следит за медиасессиями плееров: что играет, позиция, обложка; ищет текст и управляет окном. */
object MediaWatcher : SharedPreferences.OnSharedPreferenceChangeListener {

    private const val MAX_AUTO_RETRIES = 3
    private const val AUTO_RETRY_DELAY_MS = 15_000L
    private const val AUTO_HIDE_DELAY_MS = 4_000L

    private lateinit var ctx: Context
    var running = false; private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val artIo = Executors.newSingleThreadExecutor()
    private var artUri: String? = null
    private var artFromUri: Bitmap? = null
    private lateinit var prefs: SharedPreferences
    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var overlay: Overlay? = null
    private var trackKey: String? = null
    private var lastFetchedKey: String? = null
    private var autoRetries = 0
    private var pending: Future<*>? = null
    private var generation = 0

    private val component by lazy { ComponentName(ctx, MediaListener::class.java) }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pickController(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = safe { onTrack() }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            safe { updatePlayback() }
            // Другой плеер мог начать играть — перевыбираем сессию.
            if (state?.state != PlaybackState.STATE_PLAYING) refresh()
        }
        override fun onSessionDestroyed() = refresh()
    }

    private val stateListener: (NowPlaying.Change) -> Unit = { change ->
        // Полноэкранный режим работает и при выключенном окне.
        if (change == NowPlaying.Change.FULLSCREEN) refresh()
    }

    /**
     * Начать следить за плеерами. Достаточно выданного доступа к уведомлениям —
     * сам сервис уведомлений может быть и не подключён (так бывает на vivo/Xiaomi).
     */
    fun start(context: Context) {
        if (running) { refresh(); return }
        ctx = context.applicationContext
        if (!hasNotificationAccess(ctx)) return
        running = true
        Diagnostics.watcherStartedAt = System.currentTimeMillis()
        prefs = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        NowPlaying.positionProvider = ::currentPosition
        NowPlaying.retry = { trackKey = null; safe { onTrack() } }
        NowPlaying.addListener(stateListener)
        NowPlaying.controls = playerControls
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        sessionManager = msm
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
        } catch (e: SecurityException) {
            Diagnostics.record(e)
            running = false
            return
        }
        refresh()
        KeepAliveService.sync(ctx)
    }

    fun hasNotificationAccess(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(c, MediaListener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    fun stop() {
        if (!running) return
        running = false
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        controller?.unregisterCallback(callback)
        controller = null
        overlay?.destroy()
        overlay = null
        trackKey = null
        NowPlaying.removeListener(stateListener)
        NowPlaying.controls = null
        NowPlaying.clearTrack()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        if (key == Prefs.ENABLED) KeepAliveService.sync(ctx)
        if (key == Prefs.ENABLED || key == Prefs.AUTO_SHOW || Source.entries.any { it.prefKey == key }) refresh()
        if (key == Prefs.KEEP_SCREEN_ON) overlay?.applyKeepScreenOn()
    }

    fun refresh() {
        if (!running) return
        val msm = sessionManager ?: return
        val list = try {
            msm.getActiveSessions(component)
        } catch (e: SecurityException) {
            Diagnostics.record(e)
            emptyList()
        }
        pickController(list)
    }

    private fun pickController(list: List<MediaController>) = safe {
        Diagnostics.sessions = list.map { "${it.packageName} (${stateName(it.playbackState?.state)})" }
        val candidates = list.filter { isAllowed(it.packageName) }
        val chosen = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: candidates.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            chosen?.registerCallback(callback, main)
        }
        Diagnostics.chosen = chosen?.packageName
        onTrack()
    }

    /** Ошибка не должна ронять приложение: иначе Android перестаёт перезапускать сервис. */
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Diagnostics.record(e)
        }
    }

    private fun stateName(state: Int?) = when (state) {
        PlaybackState.STATE_PLAYING -> "играет"
        PlaybackState.STATE_PAUSED -> "пауза"
        PlaybackState.STATE_STOPPED -> "стоп"
        PlaybackState.STATE_BUFFERING -> "загрузка"
        null -> "нет состояния"
        else -> "состояние $state"
    }

    private fun isAllowed(pkg: String): Boolean {
        val src = Source.of(pkg)
        return prefs.getBoolean(src.prefKey, src.defaultOn)
    }

    private fun onTrack() {
        val c = controller
        val md = c?.metadata
        val enabled = prefs.getBoolean(Prefs.ENABLED, false)
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        Diagnostics.lastTitle = title.ifBlank { null }
        if (!(enabled || NowPlaying.fullscreenOpen) || md == null || title.isBlank()) {
            overlay?.hide()
            NowPlaying.clearTrack()
            trackKey = null
            return
        }
        val track = TrackNormalizer.normalize(
            Source.of(c.packageName),
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            title = title,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )

        val key = "${track.artist}|${track.title}"
        if (key != trackKey) {
            if (key != lastFetchedKey) autoRetries = 0
            trackKey = key
            lastFetchedKey = key
            NowPlaying.setTrack(track.artist, track.title)
            fetchLyrics(track)
        }
        updateArt(md)
        updatePlayback()
    }

    private val hideOnPause = Runnable { overlay?.hide() }

    /**
     * Показать или спрятать окно. В режиме автопоказа окно появляется, когда трек играет,
     * и прячется на паузе — с задержкой, чтобы не мигать при коротких паузах.
     */
    private fun syncOverlay() {
        main.removeCallbacks(hideOnPause)
        val enabled = prefs.getBoolean(Prefs.ENABLED, false)
        if (!enabled || !NowPlaying.hasTrack || !Settings.canDrawOverlays(ctx)) {
            overlay?.hide()
            return
        }
        val autoShow = prefs.getBoolean(Prefs.AUTO_SHOW, false)
        if (autoShow && !NowPlaying.isPlaying) {
            main.postDelayed(hideOnPause, AUTO_HIDE_DELAY_MS)
            return
        }
        val ov = overlay ?: Overlay(ctx, onClose = {
            prefs.edit().putBoolean(Prefs.ENABLED, false).apply()
        }).also { overlay = it }
        ov.show()
    }

    private fun fetchLyrics(track: Track) {
        val gen = ++generation
        pending?.cancel(true)
        pending = io.submit {
            val lyrics = LyricsFetcher.fetch(track.artist, track.title, track.album, track.durationMs) { status ->
                main.post { if (gen == generation) NowPlaying.setStatus(status) }
            }
            main.post {
                if (gen != generation) return@post
                NowPlaying.setLyrics(lyrics)
                // Сервер перегружен — тихо попробуем ещё раз чуть позже.
                if (lyrics is Lyrics.Error && autoRetries < MAX_AUTO_RETRIES) {
                    autoRetries++
                    main.postDelayed({
                        if (gen == generation) { trackKey = null; onTrack() }
                    }, AUTO_RETRY_DELAY_MS)
                }
            }
        }
    }

    private val playerControls = object : NowPlaying.Controls {
        override fun playPause() {
            val c = controller ?: return
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        }
        override fun next() { controller?.transportControls?.skipToNext() }
        override fun previous() { controller?.transportControls?.skipToPrevious() }
        override fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }
    }

    private fun updatePlayback() {
        val c = controller
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val duration = c?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        NowPlaying.setPlayback(playing, duration.coerceAtLeast(0))
        syncOverlay()
    }

    /** Обложка: плееры кладут её в метаданные картинкой или ссылкой. */
    private fun updateArt(md: MediaMetadata) {
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            val old = NowPlaying.art
            val same = old != null && old.width == bmp.width && old.height == bmp.height &&
                runCatching { old.sameAs(bmp) }.getOrDefault(false)
            if (!same) NowPlaying.setArt(bmp)
            return
        }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri.isNullOrBlank()) {
            NowPlaying.setArt(null)
            return
        }
        if (uri == artUri) {
            NowPlaying.setArt(artFromUri)
            return
        }
        artUri = uri
        artFromUri = null
        NowPlaying.setArt(null)
        artIo.execute {
            val loaded = runCatching { loadBitmap(uri) }.getOrNull()
            main.post {
                if (artUri == uri) {
                    artFromUri = loaded
                    NowPlaying.setArt(loaded)
                }
            }
        }
    }

    private fun loadBitmap(uri: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        return if (uri.startsWith("http://") || uri.startsWith("https://")) {
            val conn = URL(uri).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
            } finally {
                conn.disconnect()
            }
        } else {
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }

    /** Текущая позиция трека в мс с учётом времени, прошедшего с последнего обновления. */
    private fun currentPosition(): Long? {
        val s = controller?.playbackState ?: return null
        if (s.position < 0) return null
        var pos = s.position
        if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        return pos
    }
}
