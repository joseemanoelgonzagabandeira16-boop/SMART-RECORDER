package com.smartteck.smartrecorder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Captures permitted media playback through Android's official playback-capture API.
 * It writes temporary PCM WAV audio; the service later muxes it with the cropped video.
 */
internal class PlaybackAudioRecorder(
    private val projection: MediaProjection,
    private val outputFile: File
) {
    private val sampleRate = 48_000
    private val channelCount = 2
    private val bytesPerSample = 2
    private val channelMask = AudioFormat.CHANNEL_IN_STEREO
    private val audioRecord: AudioRecord
    private var output: FileOutputStream? = null
    @Volatile private var recording = false
    private var worker: Thread? = null
    @Volatile private var bytesWritten = 0L
    @Volatile private var readFailure: Throwable? = null

    init {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "A captura de áudio interno exige Android 10 ou superior."
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "Formato de áudio interno não suportado." }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 16_384))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "Não foi possível inicializar a captura de áudio interno."
        }
    }

    fun start() {
        check(!recording) { "A captura de áudio já foi iniciada." }
        outputFile.parentFile?.mkdirs()
        output = FileOutputStream(outputFile, false).also { it.write(ByteArray(44)) }
        try {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "O Android não iniciou a captura de áudio interno."
            }
        } catch (failure: Exception) {
            output?.close()
            output = null
            audioRecord.release()
            throw failure
        }
        recording = true
        worker = Thread({
            val buffer = ByteArray(16_384)
            try {
                val stream = output ?: return@Thread
                while (recording) {
                    val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        stream.write(buffer, 0, count)
                        bytesWritten += count
                    } else if (count < 0) {
                        if (!recording) break
                        if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                            throw IllegalStateException("A captura de áudio interno foi interrompida.")
                        }
                        throw IllegalStateException("Falha ao ler o áudio interno: $count")
                    }
                }
            } catch (failure: Throwable) {
                readFailure = failure
            }
        }, "SmartRecorderPlaybackAudio").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    /** Stops capture, writes the WAV header and returns true when audio samples exist. */
    fun stop(): Boolean {
        recording = false
        try {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
        } catch (_: Exception) {
        }
        try { worker?.join(5_000L) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val workerStillRunning = worker?.isAlive == true
        worker = null
        try { output?.flush(); output?.close() } catch (_: Exception) {}
        if (workerStillRunning) {
            try { audioRecord.release() } catch (_: Exception) {}
            return false
        }
        output = null
        try { audioRecord.release() } catch (_: Exception) {}
        if (bytesWritten > 0L) writeWavHeader(outputFile, bytesWritten)
        return bytesWritten > 0L && readFailure == null && outputFile.exists()
    }

    fun release() {
        try { stop() } catch (_: Exception) {}
    }

    private fun writeWavHeader(file: File, dataLength: Long) {
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeBytes("RIFF")
            writeIntLe(wav, (dataLength + 36L).coerceAtMost(0xFFFFFFFFL))
            wav.writeBytes("WAVE")
            wav.writeBytes("fmt ")
            writeIntLe(wav, 16)
            writeShortLe(wav, 1)
            writeShortLe(wav, channelCount)
            writeIntLe(wav, sampleRate)
            writeIntLe(wav, sampleRate * channelCount * bytesPerSample)
            writeShortLe(wav, channelCount * bytesPerSample)
            writeShortLe(wav, bytesPerSample * 8)
            wav.writeBytes("data")
            writeIntLe(wav, dataLength.coerceAtMost(0xFFFFFFFFL))
        }
    }

    private fun writeIntLe(file: RandomAccessFile, value: Long) {
        repeat(4) { index -> file.write((value shr (index * 8)).toInt() and 0xFF) }
    }

    private fun writeIntLe(file: RandomAccessFile, value: Int) = writeIntLe(file, value.toLong())

    private fun writeShortLe(file: RandomAccessFile, value: Int) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
    }
}
