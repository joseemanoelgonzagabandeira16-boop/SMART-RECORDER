package com.smartteck.smartrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); stopSelf() }
            ACTION_START -> { startForegroundCompat(); startCapture(intent) }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = "smart_recorder_capture"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Captura de tela", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = PendingIntent.getService(this, 2,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, channelId).setContentTitle("SMART RECORDER")
            .setContentText("Captura de tela em andamento")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .addAction(Notification.Action.Builder(null, "Parar", stopIntent).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun startCapture(command: Intent) {
        try {
            val resultCode = command.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent = if (Build.VERSION.SDK_INT >= 33) {
                command.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                command.getParcelableExtra(EXTRA_RESULT_DATA)
            } ?: throw IllegalStateException("Autorização de captura não recebida.")
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Não foi possível iniciar a captura.")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = (metrics.widthPixels / 2) * 2
            val height = (metrics.heightPixels / 2) * 2
            val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: throw IllegalStateException("Pasta de vídeos indisponível.")
            if (!directory.exists()) directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            outputFile = File(directory, "SMART_RECORDER_$stamp.mp4")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = mediaRecorder
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(30)
            mediaRecorder.setVideoEncodingBitRate(8_000_000)
            mediaRecorder.setOutputFile(outputFile!!.absolutePath)
            mediaRecorder.prepare()
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture(stopProjection = false)
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            virtualDisplay = projection!!.createVirtualDisplay("SMART_RECORDER_DISPLAY", width, height,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, Handler(Looper.getMainLooper()))
            mediaRecorder.start()
        } catch (_: Exception) {
            stopCapture()
            stopSelf()
        }
    }

    private fun stopCapture(stopProjection: Boolean = true) {
        try { recorder?.stop() } catch (_: RuntimeException) { outputFile?.delete() }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        if (stopProjection) try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        recorder = null
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.smartteck.smartrecorder.action.START_CAPTURE"
        const val ACTION_STOP = "com.smartteck.smartrecorder.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        private const val NOTIFICATION_ID = 41
    }
}
