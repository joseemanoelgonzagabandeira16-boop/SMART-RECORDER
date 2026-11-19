package com.smartteck.smartrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var dualRecorder: DualRegionRecorder? = null
    private var playbackAudioRecorder: PlaybackAudioRecorder? = null
    private var audioOutputFile: File? = null
    private var silentMainOutputFile: File? = null
    private var mainOutputFile: File? = null
    private var webcamOutputFile: File? = null
    private var combinedOutputFile: File? = null
    private var currentExportOutput: File? = null
    private var exportTransformer: Transformer? = null
    private val captureHandler = Handler(Looper.getMainLooper())
    private var autoStopTask: Runnable? = null
    private var autoStopDeadlineElapsed = 0L
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var recordingDurationMs = 0L
    private var isFinalizing = false
    private var captureWidth = 0
    private var captureHeight = 0
    private var mainX = 0.3418f
    private var mainY = 0f
    private var mainWidth = 0.3164f
    private var mainHeight = 1f
    private var webcamX = 0.78f
    private var webcamY = 0.64f
    private var webcamWidth = 0.174375f
    private var webcamHeight = 0.31f
    private var baseName = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishCapture()
            ACTION_START -> {
                mainX = intent.getFloatExtra(EXTRA_MAIN_X, mainX).coerceIn(0f, 0.99f)
                mainY = intent.getFloatExtra(EXTRA_MAIN_Y, mainY).coerceIn(0f, 0.99f)
                mainWidth = intent.getFloatExtra(EXTRA_MAIN_WIDTH, mainWidth).coerceIn(0.01f, 1f)
                mainHeight = intent.getFloatExtra(EXTRA_MAIN_HEIGHT, mainHeight).coerceIn(0.01f, 1f)
                webcamX = intent.getFloatExtra(EXTRA_CROP_X, webcamX).coerceIn(0f, 0.99f)
                webcamY = intent.getFloatExtra(EXTRA_CROP_Y, webcamY).coerceIn(0f, 0.99f)
                webcamWidth = intent.getFloatExtra(EXTRA_CROP_WIDTH, webcamWidth).coerceIn(0.01f, 1f)
                webcamHeight = intent.getFloatExtra(EXTRA_CROP_HEIGHT, webcamHeight).coerceIn(0.01f, 1f)
                recordingDurationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L).coerceAtLeast(0L)
                startForegroundCompat("Preparando captura de duas regiões", showStop = true)
                startCapture(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String, showStop: Boolean): Notification {
        val channelId = "smart_recorder_capture"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "SMART RECORDER", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = Notification.Builder(this, channelId)
            .setContentTitle("SMART RECORDER")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
        if (showStop) {
            val stopIntent = PendingIntent.getService(
                this, 2, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, "Parar", stopIntent).build())
        }
        return builder.build()
    }

    private fun startForegroundCompat(text: String, showStop: Boolean) {
        val notice = notification(text, showStop)
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= 30 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notice, types)
        } else startForeground(NOTIFICATION_ID, notice)
    }

    private fun updateNotice(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, false))
    }

    private fun startCapture(command: Intent) {
        try {
            val resultCode = command.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent = if (Build.VERSION.SDK_INT >= 33) {
                command.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                command.getParcelableExtra(EXTRA_RESULT_DATA)
            } ?: error("Autorização de captura não recebida.")

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data) ?: error("Não foi possível iniciar a captura.")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val maxSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
            val scale = minOf(1f, 1920f / maxSide.toFloat())
            captureWidth = ((metrics.widthPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
            captureHeight = ((metrics.heightPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)

            // The crop controls are calibrated for a landscape 16:9 source.
            // Never replace the user's selected crop with the full screen.
            check(captureWidth > captureHeight) {
                "A tela capturada está em retrato. Coloque o YouTube em modo horizontal e inicie novamente."
            }
            // Keep the exact selected width and height. If a saved position
            // would place a rectangle outside the source, move the rectangle
            // inward instead of silently shrinking the user's crop.
            mainWidth = mainWidth.coerceIn(0.01f, 1f)
            mainHeight = mainHeight.coerceIn(0.01f, 1f)
            mainX = mainX.coerceIn(0f, 1f - mainWidth)
            mainY = mainY.coerceIn(0f, 1f - mainHeight)
            webcamWidth = webcamWidth.coerceIn(0.01f, 1f)
            webcamHeight = webcamHeight.coerceIn(0.01f, 1f)
            webcamX = webcamX.coerceIn(0f, 1f - webcamWidth)
            webcamY = webcamY.coerceIn(0f, 1f - webcamHeight)

            val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: error("Pasta de vídeos indisponível.")
            if (!directory.exists()) directory.mkdirs()
            baseName = "SMART_RECORDER_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            mainOutputFile = File(directory, "${baseName}_9x16.mp4")
            webcamOutputFile = File(directory, "${baseName}_webcam_personalizada.mp4")
            combinedOutputFile = File(directory, "${baseName}_combined.mp4")
            audioOutputFile = File(directory, "${baseName}_internal_audio.wav")
            silentMainOutputFile = File(directory, "${baseName}_video_only.mp4")

            val hasRecordAudioPermission = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            // Android 10+ allows playback capture only when the source player permits it.
            // If the platform rejects capture, retain the existing microphone fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasRecordAudioPermission) {
                try {
                    playbackAudioRecorder = PlaybackAudioRecorder(this, projection!!, audioOutputFile!!).also { it.start() }
                } catch (_: Exception) {
                    try { playbackAudioRecorder?.release() } catch (_: Exception) {}
                    playbackAudioRecorder = null
                    audioOutputFile?.delete()
                }
            }
            val internalAudioActive = playbackAudioRecorder != null
            val mainRecordingFile = if (internalAudioActive) silentMainOutputFile!! else mainOutputFile!!
            val recordMicrophone = hasRecordAudioPermission
            val pipeline = DualRegionRecorder(
                this, captureWidth, captureHeight,
                mainX, mainY, mainWidth, mainHeight,
                webcamX, webcamY, webcamWidth, webcamHeight,
                mainRecordingFile, webcamOutputFile!!, recordMicrophone
            )
            dualRecorder = pipeline
            pipeline.prepare()

            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!isFinalizing) finishCapture(projectionAlreadyStopped = true)
                }
            }, Handler(Looper.getMainLooper()))

            pipeline.start()
            virtualDisplay = projection!!.createVirtualDisplay(
                "SMART_RECORDER_DUAL_REGION", captureWidth, captureHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                pipeline.projectionSurface, null, Handler(Looper.getMainLooper())
            ) ?: error("Não foi possível conectar a captura à tela.")

            acquireRecordingWakeLock()
            scheduleAutomaticStop()
            updateNotice("Gravando somente as áreas selecionadas.")
        } catch (failure: Exception) {
            releaseCaptureResources(stopProjection = true)
            mainOutputFile?.delete()
            silentMainOutputFile?.delete()
            audioOutputFile?.delete()
            webcamOutputFile?.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            showResultNotification("Falha ao iniciar a captura dupla: ${failure.message ?: "verifique as permissões e tente novamente."}")
            sendCaptureFinished()
            stopSelf()
        }
    }

    private fun sendCaptureFinished() {
        sendBroadcast(Intent(ACTION_CAPTURE_FINISHED).setPackage(packageName))
    }

    private fun acquireRecordingWakeLock() {
        if (recordingWakeLock?.isHeld == true) return
        recordingWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartRecorder:Capture").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseRecordingWakeLock() {
        try {
            recordingWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            recordingWakeLock = null
        }
    }

    private fun scheduleAutomaticStop() {
        autoStopTask?.let { captureHandler.removeCallbacks(it) }
        autoStopTask = null
        if (recordingDurationMs <= 0L) return

        autoStopDeadlineElapsed = SystemClock.elapsedRealtime() + recordingDurationMs
        val stopCheck = object : Runnable {
            override fun run() {
                val remaining = autoStopDeadlineElapsed - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    autoStopTask = null
                    updateNotice("Tempo encerrado. Parando a gravação e finalizando os vídeos...")
                    finishCapture()
                } else {
                    captureHandler.postDelayed(this, minOf(remaining, 1_000L))
                }
            }
        }
        autoStopTask = stopCheck
        captureHandler.postDelayed(stopCheck, minOf(recordingDurationMs, 1_000L))
    }

    private fun finishCapture(projectionAlreadyStopped: Boolean = false) {
        if (isFinalizing) return
        isFinalizing = true
        sendCaptureFinished()
        autoStopTask?.let { captureHandler.removeCallbacks(it) }
        autoStopTask = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        val pipeline = dualRecorder
        var captureStopFailed = false
        try {
            pipeline?.stop()
        } catch (_: Exception) {
            captureStopFailed = true
        }
        dualRecorder = null
        val playbackAudio = playbackAudioRecorder
        playbackAudioRecorder = null
        val hasInternalAudio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { playbackAudio?.stop() == true } catch (_: Exception) { false }
        } else false
        releaseRecordingWakeLock()
        if (projectionAlreadyStopped) projection = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (captureStopFailed || pipeline?.failure() != null) {
            finishService(error = true)
            return
        }

        val main = mainOutputFile
        val webcam = webcamOutputFile
        val silentMain = silentMainOutputFile
        if (main == null || webcam == null || !webcam.exists() || webcam.length() == 0L) {
            finishService(error = true)
            return
        }
        if (hasInternalAudio && silentMain != null && silentMain.exists() && silentMain.length() > 0L) {
            startMainAudioMux(silentMain, audioOutputFile!!, main)
            return
        }
        if (silentMain != null && silentMain.exists() && silentMain.length() > 0L) {
            silentMain.copyTo(main, overwrite = true)
            silentMain.delete()
        }
        audioOutputFile?.delete()
        if (!main.exists() || main.length() == 0L) {
            finishService(error = true)
            return
        }
        updateNotice("Combinando vídeo vertical e webcam...")
        startCombinedExport(main)
    }


    /**
     * Muxes the captured system playback WAV with the silent cropped video.
     * AudioPlaybackCapture is subject to Android's source-app capture policy;
     * it is never used to bypass protected playback.
     */
    private fun startMainAudioMux(videoFile: File, audioFile: File, outputFile: File) {
        currentExportOutput = outputFile
        updateNotice("Sincronizando áudio interno com o vídeo vertical...")
        try {
            val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(videoFile)))
                .setRemoveAudio(true)
                .build()
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioFile)))
                .setRemoveVideo(true)
                .build()
            val videoSequence = EditedMediaItemSequence.Builder(videoItem).build()
            val audioSequence = EditedMediaItemSequence.Builder(audioItem).build()
            exportTransformer = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        currentExportOutput = null
                        videoFile.delete()
                        audioFile.delete()
                        updateNotice("Áudio sincronizado. Combinando webcam...")
                        startCombinedExport(outputFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        currentExportOutput = null
                        outputFile.delete()
                        // Preserve the cropped video if audio muxing is unsupported on this device.
                        try {
                            videoFile.copyTo(outputFile, overwrite = true)
                            videoFile.delete()
                            audioFile.delete()
                            startCombinedExport(outputFile)
                        } catch (_: Exception) {
                            finishService(error = true)
                        }
                    }
                }).build()
            exportTransformer?.start(Composition.Builder(videoSequence, audioSequence).build(), outputFile.absolutePath)
        } catch (_: Exception) {
            currentExportOutput = null
            outputFile.delete()
            try {
                videoFile.copyTo(outputFile, overwrite = true)
                videoFile.delete()
                audioFile.delete()
                startCombinedExport(outputFile)
            } catch (_: Exception) {
                finishService(error = true)
            }
        }
    }

    private fun startCombinedExport(mainFile: File) {
        val webcamFile = webcamOutputFile ?: run { finishService(error = true); return }
        val combinedFile = combinedOutputFile ?: run { finishService(error = true); return }
        // Keep the overlay's actual selected dimensions, even when it is
        // larger than the vertical crop or partly outside its visible bounds.
        val centerX = (webcamX + webcamWidth / 2f - mainX) / mainWidth
        val centerY = (webcamY + webcamHeight / 2f - mainY) / mainHeight
        val overlayWidth = (webcamWidth / mainWidth).coerceAtLeast(0.02f)
        val overlayHeight = (webcamHeight / mainHeight).coerceAtLeast(0.02f)
        val mainEncodedSize = encodedRegionSize(mainWidth, mainHeight)
        val webcamEncodedSize = encodedRegionSize(webcamWidth, webcamHeight)
        // OverlaySettings scales the webcam input pixels, not normalized
        // screen percentages. Convert the desired frame-relative dimensions
        // into scale factors using both encoder resolutions.
        val overlayScaleX = overlayWidth * mainEncodedSize.first / webcamEncodedSize.first
        val overlayScaleY = overlayHeight * mainEncodedSize.second / webcamEncodedSize.second

        currentExportOutput = combinedFile
        updateNotice("Combinando as duas gravações...")
        try {
            val mainItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(mainFile))).build()
            val webcamItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(webcamFile)))
                .setRemoveAudio(true).build()
            val webcamSequence = EditedMediaItemSequence.Builder(webcamItem).build()
            val mainSequence = EditedMediaItemSequence.Builder(mainItem).build()
            val compositor = object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: List<Size>): Size {
                    return inputSizes.getOrNull(1) ?: Size(720, 1280)
                }

                override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                    if (inputId != 0) return OverlaySettings.Builder().build()
                    return OverlaySettings.Builder()
                        .setOverlayFrameAnchor(0f, 0f)
                        .setBackgroundFrameAnchor(centerX * 2f - 1f, 1f - centerY * 2f)
                        .setScale(overlayScaleX, overlayScaleY)
                        .build()
                }
            }
            val composition = Composition.Builder(webcamSequence, mainSequence)
                .setVideoCompositorSettings(compositor)
                .build()
            exportTransformer = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        currentExportOutput = null
                        finishService()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        currentExportOutput?.delete()
                        currentExportOutput = null
                        finishService(error = true)
                    }
                }).build()
            exportTransformer?.start(composition, combinedFile.absolutePath)
        } catch (_: Exception) {
            combinedFile.delete()
            currentExportOutput = null
            finishService(error = true)
        }
    }

    private fun encodedRegionSize(regionWidth: Float, regionHeight: Float): Pair<Int, Int> {
        val aspect = (captureWidth.toFloat() * regionWidth.coerceAtLeast(0.001f)) /
            (captureHeight.toFloat() * regionHeight.coerceAtLeast(0.001f))
        val longSide = if (aspect in 0.8f..1.25f) 720f else 1280f
        val width = (if (aspect >= 1f) longSide else longSide * aspect).toInt()
            .coerceAtLeast(2) / 2 * 2
        val height = (if (aspect >= 1f) longSide / aspect else longSide).toInt()
            .coerceAtLeast(2) / 2 * 2
        return width to height
    }

    private fun releaseCaptureResources(stopProjection: Boolean) {
        releaseRecordingWakeLock()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { dualRecorder?.release() } catch (_: Exception) {}
        dualRecorder = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { playbackAudioRecorder?.release() } catch (_: Exception) {}
        }
        playbackAudioRecorder = null
        if (stopProjection) try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun publishExportsToGallery(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val main = mainOutputFile ?: return 0
        val directory = main.parentFile ?: return 0
        val files = listOfNotNull(mainOutputFile, webcamOutputFile, combinedOutputFile)
            .filter { it.isFile && it.length() > 0L }
        var published = 0
        for (file in files) {
            var uri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SMART RECORDER")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: continue
                FileInputStream(file).use { input ->
                    contentResolver.openOutputStream(uri!!, "w")!!.use { output -> input.copyTo(output) }
                }
                contentResolver.update(uri!!, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                published++
            } catch (_: Exception) {
                uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            }
        }
        return published
    }

    private fun finishService(error: Boolean = false) {
        releaseRecordingWakeLock()
        if (error) currentExportOutput?.delete()
        currentExportOutput = null
        val count = if (!error) publishExportsToGallery() else 0
        val message = when {
            error -> "Não foi possível finalizar a composição. Os recortes capturados foram preservados quando válidos."
            count > 0 -> "Concluído: $count vídeos salvos em Filmes/SMART RECORDER."
            else -> "Capturas concluídas no armazenamento do aplicativo."
        }
        try { exportTransformer?.cancel() } catch (_: Exception) {}
        exportTransformer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        showResultNotification(message)
        stopSelf()
    }

    private fun showResultNotification(message: String) {
        val channelId = "smart_recorder_capture"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "SMART RECORDER", NotificationManager.IMPORTANCE_LOW))
        }
        val result = Notification.Builder(this, channelId)
            .setContentTitle("SMART RECORDER")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, result)
    }

    override fun onDestroy() {
        if (!isFinalizing) releaseCaptureResources(stopProjection = true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.smartteck.smartrecorder.action.START_CAPTURE"
        const val ACTION_STOP = "com.smartteck.smartrecorder.action.STOP_CAPTURE"
        const val ACTION_CAPTURE_FINISHED = "com.smartteck.smartrecorder.action.CAPTURE_FINISHED"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val EXTRA_MAIN_X = "main_crop_x"
        const val EXTRA_MAIN_Y = "main_crop_y"
        const val EXTRA_MAIN_WIDTH = "main_crop_width"
        const val EXTRA_MAIN_HEIGHT = "main_crop_height"
        const val EXTRA_CROP_X = "crop_x"
        const val EXTRA_CROP_Y = "crop_y"
        const val EXTRA_CROP_WIDTH = "crop_width"
        const val EXTRA_CROP_HEIGHT = "crop_height"
        const val EXTRA_DURATION_MS = "recording_duration_ms"
        private const val NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
    }
}
