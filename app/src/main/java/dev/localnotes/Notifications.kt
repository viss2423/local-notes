package dev.localnotes

import android.app.*
import android.content.Intent
import android.os.Bundle

object Notifications {
    fun show(service: Service, text: String, id: Int, type: Int, startedAt: Long = 0L) {
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("work", "Recording and processing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(service, id, Intent(service, service.javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val recording = id == 1 && (text == "Recording" || text == "Paused")
        val channel = if (recording) "live_recording" else "work"
        if (recording) manager.createNotificationChannel(NotificationChannel(
            channel, "Live recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val notification = Notification.Builder(service, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Local Notes")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .apply {
                if (recording) {
                    setWhen(startedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
                    setUsesChronometer(true)
                    addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
                }
            }.build()
        service.startForeground(id, notification, type)
    }
}
