package io.github.lyricsfloat

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Сервис уведомлений. Сам по себе он лишь даёт право читать медиасессии;
 * вся работа — в [MediaWatcher], который запускается и без этого сервиса.
 */
class MediaListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Diagnostics.connected = true
        Diagnostics.connectedAt = System.currentTimeMillis()
        MediaWatcher.start(this)
    }

    override fun onListenerDisconnected() {
        Diagnostics.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) MediaWatcher.refresh()
    }
}
