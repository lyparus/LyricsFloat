package io.github.lyricsfloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Держит приложение живым, пока включено окно с текстом: прошивки вроде vivo/Xiaomi
 * иначе убивают процесс при закрытии из «недавних» и не дают ему перезапуститься.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL = "keep_alive"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CLOSE = "io.github.lyricsfloat.CLOSE"

        /** Запустить или остановить сервис в зависимости от того, включено ли окно. */
        fun sync(ctx: Context) {
            val enabled = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.ENABLED, false)
            val intent = Intent(ctx, KeepAliveService::class.java)
            try {
                if (enabled) ctx.startForegroundService(intent) else ctx.stopService(intent)
                Diagnostics.keepAliveError = null
            } catch (e: Exception) {
                // Из фона Android может запретить запуск — тогда запустится при открытии приложения.
                Diagnostics.keepAliveError = e.javaClass.simpleName
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().putBoolean(Prefs.ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            // Не вышло стать фоновым — останавливаемся, иначе Android закроет приложение с ошибкой.
            Diagnostics.record(e)
            Diagnostics.keepAliveError = e.javaClass.simpleName
            MediaWatcher.start(this)
            stopSelf()
            return START_NOT_STICKY
        }
        Diagnostics.keepAliveRunning = true
        MediaWatcher.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        Diagnostics.keepAliveRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Окно с текстом", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывается, пока включено окно с текстом песни"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fullscreen = PendingIntent.getActivity(
            this, 1, Intent(this, FullscreenActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val close = PendingIntent.getService(
            this, 2, Intent(this, KeepAliveService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_note)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Окно с текстом включено")
            .setContentText("Текст появится, когда заиграет трек")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, "На весь экран", fullscreen).build())
            .addAction(Notification.Action.Builder(icon, "Закрыть окно", close).build())
            .build()
    }
}
