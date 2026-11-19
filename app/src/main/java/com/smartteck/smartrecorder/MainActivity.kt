package com.smartteck.smartrecorder

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale

private val Page = Color(0xFF080D18)
private val Panel = Color(0xFF111A2A)
private val PanelSoft = Color(0xFF172338)
private val Accent = Color(0xFF42D6B5)
private val TextMain = Color(0xFFF4F7FC)
private val TextMuted = Color(0xFF91A0B8)

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmartRecorderApp() }
    }
}

@UnstableApi
@Composable
private fun SmartRecorderApp() {
    var selectedTab by remember { mutableStateOf(0) }
    MaterialTheme {
        Surface(color = Page, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> CaptureScreen()
                        1 -> VideosScreen()
                        else -> SettingsScreen()
                    }
                }
                NavigationBar(containerColor = Color(0xFF0D1523), contentColor = TextMain) {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Captura") })
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) }, label = { Text("Vídeos") })
                    NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("Ajustes") })
                }
            }
        }
    }
}


@UnstableApi
@Composable
private fun CaptureScreen() {
    val context = LocalContext.current
    val cropPreferences = remember(context) { context.getSharedPreferences("smart_recorder_crop", android.content.Context.MODE_PRIVATE) }
    val timingPreferences = remember(context) { context.getSharedPreferences("smart_recorder_timing", android.content.Context.MODE_PRIVATE) }
    val displayMetrics = remember(context) {
        android.util.DisplayMetrics().also { metrics ->
            @Suppress("DEPRECATION")
            (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        }
    }
    val screenWidth = displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
    val screenHeight = displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
    var previewBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var webcamWidth by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("webcam_width", 0.174375f).coerceIn(0.04f, 0.8f)) }
    var webcamHeight by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("webcam_height", 0.31f).coerceIn(0.04f, 0.8f)) }
    var recordingMinutes by remember(timingPreferences) { mutableFloatStateOf(timingPreferences.getInt("recording_minutes", 10).toFloat().coerceIn(1f, 240f)) }
    var mainHeight by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("main_height", 1f).coerceIn(0.5f, 1f)) }
    var mainX by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("main_x", 0.25f).coerceIn(0f, 0.9f)) }
    var mainY by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("main_y", 0f).coerceIn(0f, 0.9f)) }
    var webcamX by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("webcam_x", 0.78f).coerceIn(0f, 1f - webcamWidth)) }
    var webcamY by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("webcam_y", 0.64f).coerceIn(0f, 1f - webcamHeight)) }
    var captureRunning by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent?.action == ScreenCaptureService.ACTION_CAPTURE_FINISHED) captureRunning = false
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ScreenCaptureService.ACTION_CAPTURE_FINISHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val projectionManager = remember { context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    val capturePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val bounds = previewBounds
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null && bounds.width > 0f && bounds.height > 0f) {
            val previewAspect = bounds.width / bounds.height
            val mainWidth = (mainHeight * (9f / 16f) / previewAspect).coerceIn(0.01f, 1f)
            fun screenX(x: Float, width: Float) = ((bounds.left + x * bounds.width) / screenWidth).coerceIn(0f, 1f - width * bounds.width / screenWidth)
            fun screenY(y: Float, height: Float) = ((bounds.top + y * bounds.height) / screenHeight).coerceIn(0f, 1f - height * bounds.height / screenHeight)
            val captureIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_MAIN_X, screenX(mainX, mainWidth))
                putExtra(ScreenCaptureService.EXTRA_MAIN_Y, screenY(mainY, mainHeight))
                putExtra(ScreenCaptureService.EXTRA_MAIN_WIDTH, (mainWidth * bounds.width / screenWidth).coerceIn(0.01f, 1f))
                putExtra(ScreenCaptureService.EXTRA_MAIN_HEIGHT, (mainHeight * bounds.height / screenHeight).coerceIn(0.01f, 1f))
                putExtra(ScreenCaptureService.EXTRA_CROP_X, screenX(webcamX, webcamWidth))
                putExtra(ScreenCaptureService.EXTRA_CROP_Y, screenY(webcamY, webcamHeight))
                putExtra(ScreenCaptureService.EXTRA_CROP_WIDTH, (webcamWidth * bounds.width / screenWidth).coerceIn(0.01f, 1f))
                putExtra(ScreenCaptureService.EXTRA_CROP_HEIGHT, (webcamHeight * bounds.height / screenHeight).coerceIn(0.01f, 1f))
                putExtra(ScreenCaptureService.EXTRA_DURATION_MS, recordingMinutes.toLong() * 60_000L)
            }
            captureRunning = true
            val decor = (context as? android.app.Activity)?.window?.decorView
            if (decor != null) decor.postDelayed({ context.startForegroundService(captureIntent) }, 350L)
            else context.startForegroundService(captureIntent)
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        capturePermission.launch(projectionManager.createScreenCaptureIntent())
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize().background(Page)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PlayerCropSelector(
                mainX, mainY, mainHeight, webcamX, webcamY, webcamWidth, webcamHeight,
                showGuides = !captureRunning,
                onBoundsChanged = { previewBounds = it },
                onMainChange = { x, y ->
                    mainX = x; mainY = y
                    cropPreferences.edit().putFloat("main_x", x).putFloat("main_y", y).apply()
                },
                onWebcamChange = { x, y, w, h ->
                    webcamX = x; webcamY = y; webcamWidth = w; webcamHeight = h
                    cropPreferences.edit().putFloat("webcam_x", x).putFloat("webcam_y", y).putFloat("webcam_width", w).putFloat("webcam_height", h).apply()
                }
            )
            if (!captureRunning) {
                Row(Modifier.align(Alignment.TopStart).padding(9.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC080D18)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("SMART RECORDER", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(8.dp))
                    Text("LIVE DO YOUTUBE", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (captureRunning) {
                Text("Gravação em andamento • encerramento automático em ${recordingMinutes.toInt()} min", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Button(onClick = {
                    context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                    captureRunning = false
                }, modifier = Modifier.fillMaxWidth().height(38.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE35D6A), contentColor = Page)) {
                    Text("PARAR GRAVAÇÃO", fontWeight = FontWeight.Bold)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DURAÇÃO", color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Slider(value = recordingMinutes, onValueChange = {
                        recordingMinutes = it
                        timingPreferences.edit().putInt("recording_minutes", it.toInt()).apply()
                    }, valueRange = 1f..240f, steps = 238, modifier = Modifier.weight(1f))
                    Text("${recordingMinutes.toInt()} min", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RECORTE AZUL", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Slider(value = mainHeight, onValueChange = {
                        mainHeight = it.coerceIn(0.5f, 1f)
                        cropPreferences.edit().putFloat("main_height", mainHeight).apply()
                    }, valueRange = 0.5f..1f, modifier = Modifier.weight(1f))
                    Text("${(mainHeight * 100).toInt()}%", color = Color(0xFF4D9DFF), fontSize = 10.sp)
                }
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Page)) {
                    Text("INICIAR GRAVAÇÃO", fontWeight = FontWeight.ExtraBold)
                }
                Text("Pesquise e assista à live na área acima. A gravação só começa ao tocar no botão.", color = TextMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun PlayerCropSelector(
    mainX: Float, mainY: Float, mainHeight: Float,
    webcamX: Float, webcamY: Float, webcamWidth: Float, webcamHeight: Float,
    showGuides: Boolean,
    onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    onMainChange: (Float, Float) -> Unit,
    onWebcamChange: (Float, Float, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val playerContainer = remember(context) { YouTubePlayerContainer(context) }
    DisposableEffect(playerContainer) { onDispose { playerContainer.release() } }
    val density = LocalDensity.current
    val currentMainX by rememberUpdatedState(mainX)
    val currentMainY by rememberUpdatedState(mainY)
    val currentMainHeight by rememberUpdatedState(mainHeight)
    val currentWebcamX by rememberUpdatedState(webcamX)
    val currentWebcamY by rememberUpdatedState(webcamY)
    val currentWebcamWidth by rememberUpdatedState(webcamWidth)
    val currentWebcamHeight by rememberUpdatedState(webcamHeight)

    BoxWithConstraints(
        Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)).background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val p = coordinates.positionOnScreen()
                onBoundsChanged(androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + coordinates.size.width, p.y + coordinates.size.height))
            }
    ) {
        AndroidView(factory = { playerContainer }, modifier = Modifier.fillMaxSize())
        if (showGuides) {
            val pw = with(density) { maxWidth.toPx() }
            val ph = with(density) { maxHeight.toPx() }
            val aspect = (pw / ph).coerceAtLeast(0.1f)
            val mainWidth = mainHeight * (9f / 16f) / aspect
            Box(
                Modifier.offset(x = maxWidth * mainX, y = maxHeight * mainY).width(maxWidth * mainWidth).height(maxHeight * mainHeight)
                    .border(2.dp, Color(0xFF4D9DFF), RoundedCornerShape(7.dp)).background(Color(0x222E82E6))
                    .pointerInput(pw, ph) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onMainChange((currentMainX + drag.x / pw).coerceIn(0f, 1f - mainWidth),
                                (currentMainY + drag.y / ph).coerceIn(0f, 1f - currentMainHeight))
                        }
                    }, contentAlignment = Alignment.TopCenter
            ) {
                Text("VÍDEO 9:16", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(5.dp).background(Color(0xCC174C91), RoundedCornerShape(4.dp)).padding(3.dp))
            }
            Box(
                Modifier.offset(x = maxWidth * webcamX, y = maxHeight * webcamY).width(maxWidth * webcamWidth).height(maxHeight * webcamHeight)
                    .border(2.dp, Accent, RoundedCornerShape(7.dp)).background(Color(0x553ED8B5))
                    .pointerInput(pw, ph) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onWebcamChange((currentWebcamX + drag.x / pw).coerceIn(0f, 1f - currentWebcamWidth),
                                (currentWebcamY + drag.y / ph).coerceIn(0f, 1f - currentWebcamHeight), currentWebcamWidth, currentWebcamHeight)
                        }
                    }, contentAlignment = Alignment.Center
            ) {
                Text("WEBCAM", color = TextMain, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(Accent)
                    .pointerInput(pw, ph) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val w = (currentWebcamWidth + drag.x / pw).coerceIn(0.04f, 0.8f)
                            val h = (currentWebcamHeight + drag.y / ph).coerceIn(0.04f, 0.8f)
                            onWebcamChange(currentWebcamX.coerceIn(0f, 1f - w), currentWebcamY.coerceIn(0f, 1f - h), w, h)
                        }
                    })
            }
            Text("AZUL: VÍDEO 9:16 • VERDE: WEBCAM • ARRASTE PARA AJUSTAR", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(7.dp).background(Color(0xCC080D18), RoundedCornerShape(5.dp)).padding(5.dp))
        }
    }
}

private class YouTubePlayerContainer(context: android.content.Context) : android.widget.FrameLayout(context) {
    private var customVideoView: android.view.View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    private val webView = android.webkit.WebView(context)

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.mediaPlaybackRequiresUserGesture = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(false)
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = android.webkit.WebViewClient()
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                if (customVideoView != null) { callback.onCustomViewHidden(); return }
                customVideoView = view
                customViewCallback = callback
                webView.visibility = android.view.View.GONE
                addView(view, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            override fun onHideCustomView() {
                customVideoView?.let { removeView(it) }
                customVideoView = null
                webView.visibility = android.view.View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }
        addView(webView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        webView.loadUrl("https://m.youtube.com")
    }

    fun release() {
        try {
            customVideoView?.let { removeView(it) }
            customVideoView = null
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.webChromeClient = null
            webView.webViewClient = null
            webView.destroy()
        } catch (_: Exception) {}
        removeAllViews()
    }
}

@Composable
private fun OutputTile(format: String, description: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Color(0xFF26364D))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(format, color = Accent, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            Text(description, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun VideosScreen() {
    val context = LocalContext.current
    fun loadCaptures(): List<File> {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return directory?.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    var captures by remember { mutableStateOf(loadCaptures()) }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Seus vídeos", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Vídeo vertical, webcam e composição final.", color = TextMuted, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { captures = loadCaptures() },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Accent)
            ) {
                Text("ATUALIZAR", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (captures.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Nenhuma captura ainda", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Text("Quando a captura terminar, os três vídeos aparecerão aqui.",
                        color = TextMuted, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(captures.size) { index ->
                    val file = captures[index]
                    val label = when {
                        file.name.endsWith("_9x16.mp4", ignoreCase = true) -> "VÍDEO VERTICAL 9:16"
                        file.name.endsWith("_webcam_personalizada.mp4", ignoreCase = true) -> "WEBCAM PERSONALIZADA"
                        file.name.endsWith("_combined.mp4", ignoreCase = true) -> "VÍDEO COMBINADO 9:16"
                        else -> "VÍDEO MP4"
                    }
                    Card(
                        onClick = {
                            try {
                                val videoUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val playerIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(videoUri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(playerIntent)
                            } catch (_: android.content.ActivityNotFoundException) {
                                Toast.makeText(context, "Nenhum reprodutor de vídeo encontrado.", Toast.LENGTH_LONG).show()
                            } catch (_: IllegalArgumentException) {
                                Toast.makeText(context, "Não foi possível abrir este arquivo.", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                                Text(file.name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(String.format(Locale.getDefault(), "%.1f MB", file.length() / (1024f * 1024f)),
                                    color = TextMuted, fontSize = 11.sp)
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = "Reproduzir vídeo", tint = TextMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Ajustes", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Preferências do SMART RECORDER", color = TextMuted, fontSize = 13.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Processamento no aparelho", color = TextMain, fontWeight = FontWeight.SemiBold)
                Text("A gravação e a exportação são processadas localmente. Não é necessário criar conta nem conectar um servidor.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
