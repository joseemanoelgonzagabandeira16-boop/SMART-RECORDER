package com.smartteck.smartrecorder

import android.content.Context
import android.media.MediaRecorder
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receives the MediaProjection frames as a texture and renders only the two
 * selected source rectangles into two independent encoder input surfaces.
 * No full-screen video file is created.
 */
internal class DualRegionRecorder(
    context: Context,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val mainX: Float,
    private val mainY: Float,
    private val mainWidth: Float,
    private val mainHeight: Float,
    private val webcamX: Float,
    private val webcamY: Float,
    private val webcamWidth: Float,
    private val webcamHeight: Float,
    private val mainFile: File,
    private val webcamFile: File,
    private val recordMicrophone: Boolean
) {
    private val appContext = context.applicationContext
    private val mainRecorder = createRecorder(
        mainFile, outputWidth(mainWidth, mainHeight), outputHeight(mainWidth, mainHeight), recordMicrophone
    )
    private val webcamRecorder = createRecorder(
        webcamFile, outputWidth(webcamWidth, webcamHeight), outputHeight(webcamWidth, webcamHeight), false
    )
    private var inputSurface: Surface? = null
    private var textureSurface: SurfaceTexture? = null
    private var projectionInput: Surface? = null
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: android.opengl.EGLConfig? = null
    private var mainEglSurface = EGL14.EGL_NO_SURFACE
    private var webcamEglSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var matrixLocation = -1
    private var cropLocation = -1
    private var textureLocation = -1
    private var started = false
    private var prepared = false
    @Volatile private var renderFailure: Throwable? = null

    val projectionSurface: Surface
        get() = projectionInput ?: error("O pipeline de captura não foi preparado.")

    fun prepare() {
        mainRecorder.prepare()
        webcamRecorder.prepare()
        glThread = HandlerThread("SmartRecorderGL").also { it.start() }
        glHandler = Handler(glThread!!.looper)

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display indisponível." }
        eglDisplay = display
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "Falha ao inicializar EGL." }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0) && configCount[0] > 0) {
            "Não foi possível escolher uma configuração EGL para codificação."
        }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            display, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Não foi possível criar o contexto OpenGL." }

        mainEglSurface = createEncoderSurface(mainRecorder.surface)
        webcamEglSurface = createEncoderSurface(webcamRecorder.surface)
        makeCurrent(mainEglSurface)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        cropLocation = GLES20.glGetUniformLocation(program, "uCrop")
        textureLocation = GLES20.glGetUniformLocation(program, "sTexture")

        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        textureId = texture[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        textureSurface = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(sourceWidth, sourceHeight)
            setOnFrameAvailableListener({ st ->
                try {
                    makeCurrent(mainEglSurface)
                    st.updateTexImage()
                    val matrix = FloatArray(16)
                    st.getTransformMatrix(matrix)
                    val frameTimestamp = st.timestamp
                    draw(mainEglSurface, mainRecorderWidth, mainRecorderHeight, mainX, mainY, mainWidth, mainHeight, matrix, frameTimestamp)
                    draw(webcamEglSurface, webcamRecorderWidth, webcamRecorderHeight, webcamX, webcamY, webcamWidth, webcamHeight, matrix, frameTimestamp)
                } catch (failure: Throwable) {
                    renderFailure = failure
                }
            }, glHandler)
        }
        projectionInput = Surface(textureSurface)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        prepared = true
    }

    private val mainRecorderWidth = outputWidth(mainWidth, mainHeight)
    private val mainRecorderHeight = outputHeight(mainWidth, mainHeight)
    private val webcamRecorderWidth = outputWidth(webcamWidth, webcamHeight)
    private val webcamRecorderHeight = outputHeight(webcamWidth, webcamHeight)

    fun start() {
        mainRecorder.start()
        webcamRecorder.start()
        started = true
    }

    private fun draw(
        eglSurface: android.opengl.EGLSurface,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        matrix: FloatArray,
        frameTimestamp: Long
    ) {
        makeCurrent(eglSurface)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val positions = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        val texCoords = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0)
        GLES20.glUniform4f(cropLocation, x, y, x + w, y + h)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, frameTimestamp)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "Falha ao enviar quadro ao codificador." }
    }

    fun stop() {
        try { projectionInput?.release() } catch (_: Exception) {}
        projectionInput = null
        val latch = CountDownLatch(1)
        glHandler?.post {
            try {
                textureSurface?.setOnFrameAvailableListener(null)
                textureSurface?.release()
                textureSurface = null
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    if (mainEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, mainEglSurface)
                    if (webcamEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, webcamEglSurface)
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        glThread?.quitSafely()
        glThread = null
        glHandler = null
        try { mainRecorder.stop() } catch (_: RuntimeException) { mainFile.delete() }
        try { webcamRecorder.stop() } catch (_: RuntimeException) { webcamFile.delete() }
        mainRecorder.reset()
        mainRecorder.release()
        webcamRecorder.reset()
        webcamRecorder.release()
        started = false
        prepared = false
    }

    fun release() {
        if (started || prepared) stop() else {
            try { projectionInput?.release() } catch (_: Exception) {}
            try { textureSurface?.release() } catch (_: Exception) {}
            try { mainRecorder.release() } catch (_: Exception) {}
            try { webcamRecorder.release() } catch (_: Exception) {}
        }
    }

    fun failure(): Throwable? = renderFailure

    private fun createEncoderSurface(surface: Surface): android.opengl.EGLSurface {
        val result = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(result != EGL14.EGL_NO_SURFACE) { "Não foi possível criar a superfície do codificador." }
        return result
    }

    private fun makeCurrent(surface: android.opengl.EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) { "Falha ao ativar a superfície EGL." }
    }

    private fun createRecorder(file: File, width: Int, height: Int, audio: Boolean): MediaRecorder {
        val recorder = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(appContext) else MediaRecorder()
        if (audio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (audio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioChannels(1)
        }
        recorder.setVideoSize(width, height)
        recorder.setVideoFrameRate(30)
        recorder.setVideoEncodingBitRate(6_000_000)
        recorder.setOutputFile(file.absolutePath)
        return recorder
    }

    private fun outputWidth(w: Float, h: Float): Int {
        val ratio = (sourceWidth * w) / (sourceHeight * h).coerceAtLeast(1f)
        return if (ratio >= 1f) 720 else (720f * ratio).toInt().coerceAtLeast(2) / 2 * 2
    }

    private fun outputHeight(w: Float, h: Float): Int {
        val ratio = (sourceWidth * w) / (sourceHeight * h).coerceAtLeast(1f)
        return if (ratio >= 1f) (720f / ratio).toInt().coerceAtLeast(2) / 2 * 2 else 720
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertexShader)
        GLES20.glAttachShader(result, fragmentShader)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Falha ao vincular shaders OpenGL." }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform mat4 uTexMatrix;
            uniform vec4 uCrop;
            void main() {
                vec2 uv = mix(uCrop.xy, uCrop.zw, vTexCoord);
                vec4 transformed = uTexMatrix * vec4(uv, 0.0, 1.0);
                gl_FragColor = texture2D(sTexture, transformed.xy);
            }
        """
    }
}
