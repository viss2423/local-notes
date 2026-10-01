package dev.localnotes

import android.app.*
import android.content.Intent

object Notifications {
    fun show(service: Service, text: String, id: Int, type: Int) {
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("work", "Recording and processing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(service, id, Intent(service, service.javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(service, "work")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Local Notes")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        service.startForeground(id, notification, type)
    }
}
