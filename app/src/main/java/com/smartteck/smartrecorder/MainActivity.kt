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
import androidx.compose.material3.TextButton
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
    MaterialTheme { Surface(color = Page, modifier = Modifier.fillMaxSize()) {
        if (selectedTab == 0) CaptureScreen(onOpenVideos = { selectedTab = 1 }, onOpenSettings = { selectedTab = 2 })
        else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { if (selectedTab == 1) VideosScreen() else SettingsScreen() }
            NavigationBar(containerColor = Color(0xFF0D1523), contentColor = TextMain) {
                NavigationBarItem(false, { selectedTab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Captura") })
                NavigationBarItem(selectedTab == 1, { selectedTab = 1 }, icon = { Icon(Icons.Default.VideoLibrary, null) }, label = { Text("Vídeos") })
                NavigationBarItem(selectedTab == 2, { selectedTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ajustes") })
            }
        }
    } }
}

@UnstableApi
@Composable
private fun CaptureScreen(onOpenVideos: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val prefs = remember(context) { context.getSharedPreferences("smart_recorder_crop", 0) }
    val timing = remember(context) { context.getSharedPreferences("smart_recorder_timing", 0) }
    val metrics = remember(context) { android.util.DisplayMetrics().also { m -> @Suppress("DEPRECATION") (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(m) } }
    val sw = metrics.widthPixels.toFloat().coerceAtLeast(1f); val sh = metrics.heightPixels.toFloat().coerceAtLeast(1f)
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var url by remember { mutableStateOf("") }; var live by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }; var running by remember { mutableStateOf(false) }; var preparing by remember { mutableStateOf(false) }
    var mainGuide by remember { mutableStateOf(true) }; var webcamGuide by remember { mutableStateOf(true) }
    var mainX by remember { mutableFloatStateOf(prefs.getFloat("main_x", .25f)) }; var mainY by remember { mutableFloatStateOf(prefs.getFloat("main_y", 0f)) }
    var mainH by remember { mutableFloatStateOf(prefs.getFloat("main_height", 1f).coerceIn(.5f, 1f)) }
    var wx by remember { mutableFloatStateOf(prefs.getFloat("webcam_x", .78f)) }; var wy by remember { mutableFloatStateOf(prefs.getFloat("webcam_y", .64f)) }
    var ww by remember { mutableFloatStateOf(prefs.getFloat("webcam_width", .174f).coerceIn(.04f, .8f)) }; var wh by remember { mutableFloatStateOf(prefs.getFloat("webcam_height", .31f).coerceIn(.04f, .8f)) }
    var minutes by remember { mutableFloatStateOf(timing.getInt("recording_minutes", 1).toFloat().coerceIn(1f, 240f)) }
    val player = remember(context) { YouTubePlayerContainer(context) }; DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(live, activity) {
        @Suppress("DEPRECATION")
        activity?.window?.decorView?.systemUiVisibility = if (live) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) else View.SYSTEM_UI_FLAG_VISIBLE
        onDispose { @Suppress("DEPRECATION") activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE }
    }
    DisposableEffect(context) { val receiver = object : BroadcastReceiver() { override fun onReceive(c: android.content.Context?, i: Intent?) { if (i?.action == ScreenCaptureService.ACTION_CAPTURE_FINISHED) { running = false; preparing = false; expanded = false; player.seekToLive() } } }; ContextCompat.registerReceiver(context, receiver, IntentFilter(ScreenCaptureService.ACTION_CAPTURE_FINISHED), ContextCompat.RECEIVER_NOT_EXPORTED); onDispose { context.unregisterReceiver(receiver) } }
    val manager = remember { context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data; val r = bounds
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null && r.width > 0f && r.height > 0f) {
            val aspect = r.width / r.height; val mw = (mainH * 9f / 16f / aspect).coerceIn(.01f, 1f)
            fun sx(x: Float, w: Float) = ((r.left + x * r.width) / sw).coerceIn(0f, 1f - w * r.width / sw)
            fun sy(y: Float, h: Float) = ((r.top + y * r.height) / sh).coerceIn(0f, 1f - h * r.height / sh)
            val intent = Intent(context, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_START; putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode); putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data); putExtra(ScreenCaptureService.EXTRA_MAIN_X, sx(mainX,mw)); putExtra(ScreenCaptureService.EXTRA_MAIN_Y, sy(mainY,mainH)); putExtra(ScreenCaptureService.EXTRA_MAIN_WIDTH, (mw*r.width/sw).coerceIn(.01f,1f)); putExtra(ScreenCaptureService.EXTRA_MAIN_HEIGHT, (mainH*r.height/sh).coerceIn(.01f,1f)); putExtra(ScreenCaptureService.EXTRA_CROP_X, sx(wx,ww)); putExtra(ScreenCaptureService.EXTRA_CROP_Y, sy(wy,wh)); putExtra(ScreenCaptureService.EXTRA_CROP_WIDTH, (ww*r.width/sw).coerceIn(.01f,1f)); putExtra(ScreenCaptureService.EXTRA_CROP_HEIGHT, (wh*r.height/sh).coerceIn(.01f,1f)); putExtra(ScreenCaptureService.EXTRA_DURATION_MS, minutes.toLong()*60_000L) }
            player.seekBack(minutes.toInt()*60) { ok -> if (!ok) { preparing = false; Toast.makeText(context, "Esta live não disponibiliza esse tempo de DVR para voltar.", Toast.LENGTH_LONG).show() } else { running = true; preparing = false; expanded = false; mainGuide = false; webcamGuide = false; activity?.window?.decorView?.postDelayed({ context.startForegroundService(intent) }, 800L) ?: context.startForegroundService(intent) } }
        } else { preparing = false; Toast.makeText(context, "Permissão de captura não concedida.", Toast.LENGTH_SHORT).show() }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) captureLauncher.launch(manager.createScreenCaptureIntent()) else { preparing = false; Toast.makeText(context, "Permissão de microfone necessária. Áudio interno do YouTube ainda não é gravado.", Toast.LENGTH_LONG).show() } }
    val noticeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    if (!live) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Page, Color(0xFF101D31)))).padding(22.dp), verticalArrangement = Arrangement.Center) {
            Text("SMART RECORDER", color = Accent, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            Spacer(Modifier.height(14.dp)); Text("Grave sua live do seu jeito.", color = TextMain, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp)); Text("Cole o link do YouTube. A live será aberta dentro do aplicativo.", color = TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(22.dp)); androidx.compose.material3.OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Link da live do YouTube") }, placeholder = { Text("youtube.com/live/...") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(12.dp)); Button(onClick = { val valid = normalizeYoutubeLiveUrl(url); if (valid == null) Toast.makeText(context, "Link inválido. Use youtube.com/live, youtube.com/watch ou youtu.be.", Toast.LENGTH_LONG).show() else { player.loadYoutubeUrl(valid); live = true } }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Page)) { Text("VER LIVE", fontWeight = FontWeight.ExtraBold) }
            Spacer(Modifier.height(18.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onOpenVideos, modifier = Modifier.weight(1f)) { Text("MEUS VÍDEOS") }; OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("AJUSTES") } }
        }
    } else Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerCropSelector(player, mainX, mainY, mainH, wx, wy, ww, wh, !running && !preparing && (mainGuide || webcamGuide), { bounds = it },
            { x,y -> mainX=x; mainY=y; prefs.edit().putFloat("main_x",x).putFloat("main_y",y).apply() },
            { x,y,w,h -> wx=x; wy=y; ww=w; wh=h; prefs.edit().putFloat("webcam_x",x).putFloat("webcam_y",y).putFloat("webcam_width",w).putFloat("webcam_height",h).apply() })
        if (!running && !preparing) {
            TextButton(onClick = { live = false; expanded = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("VOLTAR", color = TextMain) }
            if (expanded) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp, start = 12.dp, end = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xEE101A2A)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("CONTROLES", color = Accent, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("DURAÇÃO", color = TextMain, fontSize = 10.sp); Slider(minutes, { minutes=it; timing.edit().putInt("recording_minutes",it.toInt()).apply() }, valueRange=1f..240f, steps=238, modifier=Modifier.weight(1f)); Text("${minutes.toInt()} min", color=Accent) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { androidx.compose.material3.FilterChip(mainGuide, { mainGuide=!mainGuide }, label={ Text("RECORTE AZUL") }); androidx.compose.material3.FilterChip(webcamGuide, { webcamGuide=!webcamGuide }, label={ Text("WEBCAM VERDE") }) }
                Text("Altura vídeo: ${(mainH*100).toInt()}%", color=TextMuted, fontSize=10.sp); Slider(mainH,{ mainH=it; prefs.edit().putFloat("main_height",it).apply() },valueRange=.5f..1f)
                Text("Largura webcam: ${(ww*100).toInt()}%",color=TextMuted,fontSize=10.sp); Slider(ww,{ ww=it.coerceIn(.04f,.8f); wx=wx.coerceIn(0f,1f-ww); prefs.edit().putFloat("webcam_width",ww).putFloat("webcam_x",wx).apply() },valueRange=.04f.. .8f)
                Text("Altura webcam: ${(wh*100).toInt()}%",color=TextMuted,fontSize=10.sp); Slider(wh,{ wh=it.coerceIn(.04f,.8f); wy=wy.coerceIn(0f,1f-wh); prefs.edit().putFloat("webcam_height",wh).putFloat("webcam_y",wy).apply() },valueRange=.04f.. .8f)
                Button(onClick = { preparing=true; if (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) noticeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=Page)) { Text("INICIAR GRAVAÇÃO",fontWeight=FontWeight.ExtraBold) }
            }
            Button(onClick={ expanded=!expanded },modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=14.dp).size(62.dp),shape=CircleShape,colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=Page),contentPadding=androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(if(expanded) "×" else "●",fontSize=27.sp,fontWeight=FontWeight.Black) }
        }
        if (preparing) Text("Preparando gravação…",color=TextMain,modifier=Modifier.align(Alignment.BottomCenter).padding(22.dp).background(Color(0xCC080D18),RoundedCornerShape(10.dp)).padding(12.dp))
        if (running) Row(Modifier.align(Alignment.BottomCenter).padding(14.dp).clip(CircleShape).background(Color(0xDD080D18)).padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) { Text("● GRAVANDO",color=Color(0xFFFF6474),fontWeight=FontWeight.ExtraBold); Spacer(Modifier.width(10.dp)); Button(onClick={ context.startService(Intent(context,ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)) },colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE35D6A),contentColor=Page)) { Text("PARAR") } }
    }
}

private fun normalizeYoutubeLiveUrl(raw: String): String? = try {
    val input=raw.trim(); if(input.isBlank() || input.length>2048) return null
    val uri=Uri.parse(if(input.startsWith("http://")||input.startsWith("https://")) input else "https://$input")
    val host=(uri.host ?: return null).lowercase(Locale.ROOT)
    if(host !in setOf("youtube.com","www.youtube.com","m.youtube.com","youtu.be","www.youtu.be")) return null
    val path=uri.path.orEmpty(); val valid=if(host.contains("youtu.be")) path.trim('/').isNotEmpty() else path.startsWith("/live/") || path=="/watch"
    if(!valid) return null
    Uri.Builder().scheme("https").authority(if(host.contains("youtu.be")) "youtu.be" else "m.youtube.com").path(path).encodedQuery(uri.encodedQuery).build().toString()
} catch (_: Exception) { null }

@Composable
private fun PlayerCropSelector(
    playerContainer: YouTubePlayerContainer,
    mainX: Float, mainY: Float, mainHeight: Float,
    webcamX: Float, webcamY: Float, webcamWidth: Float, webcamHeight: Float,
    showGuides: Boolean,
    onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    onMainChange: (Float, Float) -> Unit,
    onWebcamChange: (Float, Float, Float, Float) -> Unit
) {
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
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun loadYoutubeUrl(url: String) { webView.loadUrl(url) }

    /** Rewinds only when the YouTube player exposes a seekable DVR window. */
    fun seekBack(seconds: Int, callback: (Boolean) -> Unit) {
        val safeSeconds = seconds.coerceIn(1, 14_400)
        val prepareScript = """(function(){
            var v=document.querySelector('video');
            if(!v || !v.seekable || v.seekable.length===0) return -1;
            var start=v.seekable.start(0), end=v.seekable.end(v.seekable.length-1);
            var now=Math.min(v.currentTime,end);
            if(!isFinite(now) || now-start < $safeSeconds-1) return -1;
            var target=Math.max(start,now-$safeSeconds);
            try { v.currentTime=target; return target; } catch(e) { return -1; }
        })();""".trimIndent()
        webView.evaluateJavascript(prepareScript) { raw ->
            val target = raw?.toDoubleOrNull()
            if (target == null || target < 0.0) {
                callback(false)
            } else {
                waitForSeek(target, 0, callback)
            }
        }
    }

    private fun waitForSeek(target: Double, attempt: Int, callback: (Boolean) -> Unit) {
        val checkScript = """(function(){var v=document.querySelector('video');
            return !!(v && !v.seeking && v.readyState>=2 && Math.abs(v.currentTime-$target)<1.75);
        })();""".trimIndent()
        webView.evaluateJavascript(checkScript) { raw ->
            if (raw == "true") callback(true)
            else if (attempt >= 30) callback(false)
            else mainHandler.postDelayed({ waitForSeek(target, attempt + 1, callback) }, 300L)
        }
    }

    /** Moves playback to the newest seekable point after capture is complete. */
    fun seekToLive() {
        val script = """(function(){var v=document.querySelector('video');
            if(!v || !v.seekable || v.seekable.length===0) return false;
            var end=v.seekable.end(v.seekable.length-1);
            try { v.currentTime=Math.max(v.seekable.start(v.seekable.length-1),end-0.25); return true; }
            catch(e) { return false; }
        })();""".trimIndent()
        webView.evaluateJavascript(script, null)
    }

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
        webView.loadUrl("about:blank")
    }

    fun release() {
        try {
            customVideoView?.let { removeView(it) }
            customVideoView = null
            webView.stopLoading()
            webView.loadUrl("about:blank")
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
