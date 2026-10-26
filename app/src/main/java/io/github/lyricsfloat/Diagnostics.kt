package io.github.lyricsfloat

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Что происходит внутри: для экрана «Диагностика» и отчёта, который можно скопировать. */
object Diagnostics {
    var connected = false
    var connectedAt = 0L
    var watcherStartedAt = 0L
    var keepAliveRunning = false
    var keepAliveError: String? = null
    var sessions: List<String> = emptyList()
    var chosen: String? = null
    var lastTitle: String? = null
    var lastError: String? = null
    var lastErrorAt = 0L

    fun record(e: Throwable) {
        lastError = Log.getStackTraceString(e).take(2000)
        lastErrorAt = System.currentTimeMillis()
    }

    fun crashFile(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun report(ctx: Context): String {
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        val pm = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val listeners = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return buildString {
            appendLine("Версия: ${pm.versionName}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Доступ к уведомлениям: ${if (ctx.packageName in listeners) "есть" else "НЕТ"}")
            appendLine("Показ поверх окон: ${if (Settings.canDrawOverlays(ctx)) "есть" else "НЕТ"}")
            appendLine("Сервис уведомлений: ${if (connected) "подключён с ${fmt.format(Date(connectedAt))}" else "не подключён"}")
            appendLine("Слежение за плеером: ${if (MediaWatcher.running) "работает с ${fmt.format(Date(watcherStartedAt))}" else "НЕ РАБОТАЕТ"}")
            appendLine("Фоновый режим: ${if (keepAliveRunning) "включён" else "выключен"}${keepAliveError?.let { " (ошибка запуска: $it)" } ?: ""}")
            val pm2 = ctx.getSystemService(android.os.PowerManager::class.java)
            appendLine("Ограничение батареи: ${if (pm2.isIgnoringBatteryOptimizations(ctx.packageName)) "снято" else "ЕСТЬ"}")
            appendLine("Плееры: ${if (sessions.isEmpty()) "не видно ни одного" else sessions.joinToString()}")
            appendLine("Выбран: ${chosen ?: "—"}")
            appendLine("Трек: ${lastTitle ?: "—"}")
            appendLine("Текст: ${when (val l = NowPlaying.lyrics) {
                is Lyrics.Synced -> "с таймкодами, ${l.source}"
                is Lyrics.Plain -> "без таймкодов, ${l.source}"
                null -> "ищется"
                else -> l.javaClass.simpleName
            }}")
            appendLine("Офлайн-кэш: ${LyricsCache.count()} текстов, ${LyricsCache.sizeBytes() / 1024} КБ")
            appendLine("Окно включено: ${ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.ENABLED, false)}")
            lastError?.let { appendLine("Последняя ошибка (${fmt.format(Date(lastErrorAt))}):\n$it") }
            val crash = crashFile(ctx)
            if (crash.exists()) appendLine("Последнее падение:\n${crash.readText().take(3000)}")
        }
    }
}

/** Сохраняет текст падения, чтобы показать его в диагностике после перезапуска. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
                Diagnostics.crashFile(this).writeText("$time\n${Log.getStackTraceString(e)}")
            }
            previous?.uncaughtException(thread, e)
        }
        LyricsCache.init(this)
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        NowPlaying.setAutoScroll(prefs.getBoolean(Prefs.AUTO_SCROLL, false))
        NowPlaying.setAutoScrollSpeed(prefs.getFloat(Prefs.AUTO_SCROLL_SPEED, 1f))
        // Следим за плеером с самого запуска процесса, не дожидаясь сервиса уведомлений.
        runCatching { MediaWatcher.start(this) }.onFailure { Diagnostics.record(it) }
    }
}
