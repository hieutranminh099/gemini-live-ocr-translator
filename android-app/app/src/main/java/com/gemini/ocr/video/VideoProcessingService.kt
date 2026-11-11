package com.gemini.ocr.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.gemini.ocr.MainActivity

class VideoProcessingService:Service(){
    private var finishing=false
    override fun onCreate(){super.onCreate()
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("video-processing","Dịch video",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,1,Intent(this,VideoActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,2,Intent(this,VideoProcessingService::class.java).setAction("pause"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"video-processing").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Gemini OCR — Dịch video").setContentText("Đang xử lý và lưu checkpoint. Mở để xem tiến độ.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Tạm dừng",pause).build()).build()
        if(Build.VERSION.SDK_INT>=35)startForeground(703,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if(Build.VERSION.SDK_INT>=29)startForeground(703,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(703,notification)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val jobs=VideoJobs.get(this)
        if(intent?.action=="pause"){jobs.pause();return START_NOT_STICKY}
        val id=intent?.getStringExtra("project")?:run{stopSelf();return START_NOT_STICKY}
        finishing=false
        jobs.execute(id,intent.getBooleanExtra("export",false)){finishing=true;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){VideoJobs.get(this).pause();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){if(!finishing)VideoJobs.get(this).pause();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
