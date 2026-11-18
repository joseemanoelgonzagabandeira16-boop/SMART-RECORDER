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
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.Crop
import androidx.media3.transformer.Effects
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var currentExportOutput: File? = null
    private var exportTransformer: Transformer? = null
    private var cropX = 0.58f
    private var cropY = 0.12f
    private var cropSize = 0.31f
    private var exportStage = 0
    private var isCombinedExport = false
    private var isFinalizing = false
    private var captureWidth = 0
    private var captureHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishCaptureAndExport()
            ACTION_START -> {
                cropX = intent.getFloatExtra(EXTRA_CROP_X, cropX).coerceIn(0f, 0.80f)
                cropY = intent.getFloatExtra(EXTRA_CROP_Y, cropY).coerceIn(0f, 0.80f)
                cropSize = intent.getFloatExtra(EXTRA_CROP_SIZE, cropSize).coerceIn(0.20f, 0.48f)
                startForegroundCompat("Captura de tela em andamento", showStop = true)
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
            val stopIntent = PendingIntent.getService(this, 2,
                Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Parar", stopIntent).build())
        }
        return builder.build()
    }

    private fun startForegroundCompat(text: String, showStop: Boolean) {
        val notice = notification(text, showStop)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notice)
        }
    }

    private fun updateNotice(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, showStop = false))
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
            // Limita o maior lado a 1920 px para melhorar a compatibilidade com encoders móveis.
            val sourceMaxSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
            val scale = minOf(1f, 1920f / sourceMaxSide.toFloat())
            val width = ((metrics.widthPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val height = ((metrics.heightPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
            captureWidth = width
            captureHeight = height
            val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: throw IllegalStateException("Pasta de vídeos indisponível.")
            if (!directory.exists()) directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
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
                    if (!isFinalizing) finishCaptureAndExport(projectionAlreadyStopped = true)
                }
            }, Handler(Looper.getMainLooper()))
            virtualDisplay = projection!!.createVirtualDisplay("SMART_RECORDER_DISPLAY", width, height,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, Handler(Looper.getMainLooper()))
            mediaRecorder.start()
        } catch (_: Exception) {
            outputFile?.delete()
            releaseCaptureResources(stopProjection = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            showResultNotification("Não foi possível iniciar a captura. Verifique a autorização e tente novamente.")
            stopSelf()
        }
    }

    private fun finishCaptureAndExport(projectionAlreadyStopped: Boolean = false) {
        if (isFinalizing) return
        isFinalizing = true
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            outputFile?.delete()
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        if (projectionAlreadyStopped) projection = null
        val source = outputFile
        if (source == null || !source.exists() || source.length() == 0L) {
            finishService(error = true)
            return
        }
        updateNotice("Preparando vídeo vertical e webcam...")
        startNextExport(source)
    }

    private fun startNextExport(source: File) {
        val directory = source.parentFile ?: run { finishService(); return }
        val base = source.nameWithoutExtension
        val output: File
        val crop: Crop
        val sourceAspect = if (captureWidth > 0 && captureHeight > 0) {
            captureWidth.toFloat() / captureHeight.toFloat()
        } else 16f / 9f
        if (exportStage == 0) {
            // Recorta 9:16 corretamente tanto de fontes horizontais quanto verticais.
            val targetAspect = 9f / 16f
            val leftFraction: Float
            val rightFraction: Float
            val topFraction: Float
            val bottomFraction: Float
            if (sourceAspect > targetAspect) {
                val cropWidth = (targetAspect / sourceAspect).coerceIn(0.01f, 1f)
                val centerX = (cropX + cropSize / 2f).coerceIn(cropWidth / 2f, 1f - cropWidth / 2f)
                leftFraction = centerX - cropWidth / 2f
                rightFraction = centerX + cropWidth / 2f
                topFraction = 0f
                bottomFraction = 1f
            } else {
                val cropHeight = (sourceAspect / targetAspect).coerceIn(0.01f, 1f)
                val selectedCenterY = cropY + (cropSize * sourceAspect) / 2f
                val centerY = selectedCenterY.coerceIn(cropHeight / 2f, 1f - cropHeight / 2f)
                leftFraction = 0f
                rightFraction = 1f
                topFraction = centerY - cropHeight / 2f
                bottomFraction = centerY + cropHeight / 2f
            }
            crop = Crop(
                2f * leftFraction - 1f,
                2f * rightFraction - 1f,
                1f - 2f * bottomFraction,
                1f - 2f * topFraction
            )
            output = File(directory, "${base}_9x16.mp4")
            updateNotice("Gerando vídeo vertical 9:16...")
        } else {
            // Converte a seleção normalizada em um quadrado de pixels na resolução capturada.
            val squareWidth = minOf(cropSize, 1f / sourceAspect).coerceAtLeast(0.01f)
            val squareHeight = (squareWidth * sourceAspect).coerceIn(0.01f, 1f)
            val leftFraction = cropX.coerceIn(0f, 1f - squareWidth)
            val topFraction = cropY.coerceIn(0f, 1f - squareHeight)
            val rightFraction = leftFraction + squareWidth
            val bottomFraction = topFraction + squareHeight
            crop = Crop(
                2f * leftFraction - 1f,
                2f * rightFraction - 1f,
                1f - 2f * bottomFraction,
                1f - 2f * topFraction
            )
            output = File(directory, "${base}_webcam_1x1.mp4")
            updateNotice("Gerando webcam quadrada 1:1...")
        }
        currentExportOutput = output
        try {
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setEffects(Effects(emptyList(), listOf(crop)))
                .build()
            if (exportTransformer == null) {
                exportTransformer = Transformer.Builder(this)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            currentExportOutput = null
                            if (isCombinedExport) {
                                isCombinedExport = false
                                finishService()
                            } else {
                                exportStage++
                                if (exportStage < 2) startNextExport(source)
                                else startCombinedExport(source)
                            }
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
                    })
                    .build()
            }
            exportTransformer?.start(edited, output.absolutePath)
        } catch (_: Exception) {
            output.delete()
            currentExportOutput = null
            finishService(error = true)
        }
    }

    /**
     * Combina o vídeo vertical como fundo e a webcam como segunda faixa,
     * usando exatamente a região que o usuário selecionou na tela de captura.
     */
    private fun startCombinedExport(source: File) {
        val directory = source.parentFile ?: run { finishService(error = true); return }
        val base = source.nameWithoutExtension
        val verticalFile = File(directory, "${base}_9x16.mp4")
        val webcamFile = File(directory, "${base}_webcam_1x1.mp4")
        val combinedFile = File(directory, "${base}_combined.mp4")

        if (!verticalFile.exists() || verticalFile.length() == 0L ||
            !webcamFile.exists() || webcamFile.length() == 0L
        ) {
            finishService(error = true)
            return
        }

        val sourceAspect = if (captureWidth > 0 && captureHeight > 0) {
            captureWidth.toFloat() / captureHeight.toFloat()
        } else 16f / 9f

        val targetAspect = 9f / 16f
        var verticalLeft = 0f
        var verticalTop = 0f
        var verticalWidth = 1f
        var verticalHeight = 1f

        if (sourceAspect > targetAspect) {
            verticalWidth = (targetAspect / sourceAspect).coerceIn(0.01f, 1f)
            val centerX = (cropX + cropSize / 2f)
                .coerceIn(verticalWidth / 2f, 1f - verticalWidth / 2f)
            verticalLeft = centerX - verticalWidth / 2f
        } else {
            verticalHeight = (sourceAspect / targetAspect).coerceIn(0.01f, 1f)
            val selectedCenterY = cropY + (cropSize * sourceAspect) / 2f
            val centerY = selectedCenterY
                .coerceIn(verticalHeight / 2f, 1f - verticalHeight / 2f)
            verticalTop = centerY - verticalHeight / 2f
        }

        val squareWidth = minOf(cropSize, 1f / sourceAspect).coerceAtLeast(0.01f)
        val squareHeight = (squareWidth * sourceAspect).coerceIn(0.01f, 1f)
        val webcamLeft = cropX.coerceIn(0f, 1f - squareWidth)
        val webcamTop = cropY.coerceIn(0f, 1f - squareHeight)

        val centerXInVertical = (
            (webcamLeft + squareWidth / 2f - verticalLeft) / verticalWidth
        ).coerceIn(0f, 1f)
        val centerYInVertical = (
            (webcamTop + squareHeight / 2f - verticalTop) / verticalHeight
        ).coerceIn(0f, 1f)
        val overlayWidth = (squareWidth / verticalWidth).coerceIn(0.04f, 1f)

        currentExportOutput = combinedFile
        isCombinedExport = true
        updateNotice("Combinando vídeo vertical e webcam automaticamente...")

        try {
            val mainItem = EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(verticalFile))
            ).build()
            val webcamItem = EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(webcamFile))
            ).setRemoveAudio(true).build()

            // Media3 draws the first sequence on top of the later sequences.
            // Put the square webcam first (foreground) and the vertical video second (background).
            val webcamSequence = EditedMediaItemSequence.Builder(webcamItem).build()
            val mainSequence = EditedMediaItemSequence.Builder(mainItem).build()

            val compositorSettings = object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: List<Size>): Size {
                    // The second input is the vertical background and defines the final 9:16 size.
                    return inputSizes.getOrNull(1)
                        ?: Size(captureWidth.coerceAtLeast(2), captureHeight.coerceAtLeast(2))
                }

                override fun getOverlaySettings(
                    inputId: Int,
                    presentationTimeUs: Long
                ): OverlaySettings {
                    // Input 0 is the webcam foreground; input 1 is the full-frame vertical background.
                    if (inputId != 0) return StaticOverlaySettings.Builder().build()
                    return StaticOverlaySettings.Builder()
                        .setOverlayFrameAnchor(0f, 0f)
                        .setBackgroundFrameAnchor(
                            centerXInVertical * 2f - 1f,
                            1f - centerYInVertical * 2f
                        )
                        // The compositor already compensates for the square overlay's aspect ratio.
                        // Use the same scale on both axes to keep the webcam square.
                        .setScale(overlayWidth, overlayWidth)
                        .build()
                }
            }

            val composition = Composition.Builder(webcamSequence, mainSequence)
                .setVideoCompositorSettings(compositorSettings)
                .build()

            exportTransformer?.start(composition, combinedFile.absolutePath)
                ?: throw IllegalStateException("Processador de vídeo indisponível.")
        } catch (_: Exception) {
            combinedFile.delete()
            currentExportOutput = null
            isCombinedExport = false
            finishService(error = true)
        }
    }

    private fun releaseCaptureResources(stopProjection: Boolean) {
        try { recorder?.stop() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        if (stopProjection) try { projection?.stop() } catch (_: Exception) {}
        recorder = null
        virtualDisplay = null
        projection = null
    }

    private fun finishService(error: Boolean = false) {
        if (error) currentExportOutput?.delete()
        currentExportOutput = null
        val resultText = if (error && exportStage >= 2) {
            "Não foi possível combinar os vídeos. Os arquivos vertical e webcam foram mantidos."
        } else if (error) {
            "Não foi possível concluir os recortes. A gravação original foi mantida."
        } else {
            "Vídeos vertical, webcam e combinado concluídos."
        }
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        exportTransformer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        showResultNotification(resultText)
        stopSelf()
    }

    private fun showResultNotification(message: String) {
        val channelId = "smart_recorder_capture"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "SMART RECORDER", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val resultNotification = Notification.Builder(this, channelId)
            .setContentTitle("SMART RECORDER")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, resultNotification)
    }

    override fun onDestroy() {
        if (!isFinalizing) releaseCaptureResources(stopProjection = true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.smartteck.smartrecorder.action.START_CAPTURE"
        const val ACTION_STOP = "com.smartteck.smartrecorder.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val EXTRA_CROP_X = "crop_x"
        const val EXTRA_CROP_Y = "crop_y"
        const val EXTRA_CROP_SIZE = "crop_size"
        private const val NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
    }
}
