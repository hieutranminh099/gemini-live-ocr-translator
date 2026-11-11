package com.gemini.ocr.batch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** One foreground owner; redelivery resumes only a durable active batch. */
class BatchProcessingService : Service() {
    @Volatile private var finishing = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("batch-video-processing", "Dịch video hàng loạt", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 51, Intent(this, BatchActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 52, Intent(this, BatchProcessingService::class.java).setAction("pause"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "batch-video-processing").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Gemini OCR · Dịch hàng loạt").setContentText("Xử lý từng video và lưu checkpoint; chạm để xem tiến độ.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Tạm dừng", pause).build()).build()
        if (Build.VERSION.SDK_INT >= 35) startForeground(704, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(704, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(704, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobs = BatchJobs.get(this)
        if (intent?.action == "pause") {
            jobs.pause()
            if (jobs.busy.value == null) { finishing = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("batch") ?: run { stopSelf(); return START_NOT_STICKY }
        if (jobs.busy.value != null && jobs.busy.value != id) return START_REDELIVER_INTENT
        finishing = false
        // Duplicate start intents must not leave the service alive with an obsolete startId.
        try {
            jobs.execute(id) { finishing = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        } catch (_: Exception) {
            // An unreadable checkpoint or unavailable reservation must not cause a restart loop.
            finishing = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        return if (finishing) START_NOT_STICKY else START_REDELIVER_INTENT
    }
    override fun onTimeout(startId: Int, fgsType: Int) { BatchJobs.get(this).pause(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { if (!finishing) BatchJobs.get(this).pause(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
