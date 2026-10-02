package dev.localnotes

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.concurrent.LinkedBlockingQueue

/** Downloads models in the background (they are large) and keeps going when the app is closed. */
class DownloadService : Service() {
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { cancelled = true; queue.clear(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("model") ?: return START_NOT_STICKY
        Notifications.show(this, "Downloading models", 3, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (id !in queue && id !in Live.downloads.value) {
            queue.put(id); Live.downloads.value = Live.downloads.value + (id to 0f)
        }
        if (worker == null) worker = Thread { work() }.also { it.name = "LocalNotes-download"; it.start() }
        return START_NOT_STICKY
    }

    private fun work() {
        try {
            while (true) {
                val id = queue.poll() ?: break
                val spec = Models.all.first { it.id == id }
                cancelled = false
                try {
                    // Re-checking imported audio needs the small voice detector too.
                    if (spec.role == Role.ACCURATE) Models.download(this, Models.vad, { cancelled }) { _, _ -> }
                    Models.download(this, spec, { cancelled }) { done, total ->
                        Live.downloads.value = Live.downloads.value + (id to done.toFloat() / maxOf(total, 1))
                    }
                    Models.choose(this, spec)
                    Live.status.value = "${spec.name} is ready."
                } catch (error: InterruptedException) {
                    Live.status.value = "Download paused. Tap Download again to resume."
                } catch (error: Throwable) {
                    Live.status.value = "${spec.name}: ${error.message}"
                } finally {
                    Live.downloads.value = Live.downloads.value - id
                }
            }
        } finally {
            worker = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
}
