package com.smartteck.smartrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmartRecorderApp() }
    }
}

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

@Composable
private fun CaptureScreen() {
    val context = LocalContext.current
    val cropPreferences = remember(context) {
        context.getSharedPreferences("smart_recorder_crop", android.content.Context.MODE_PRIVATE)
    }
    var webcamWidth by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("webcam_width", cropPreferences.getFloat("webcam_size", 0.31f) * 9f / 16f).coerceIn(0.05f, 0.95f)) }
    var webcamHeight by remember(cropPreferences) { mutableFloatStateOf(cropPreferences.getFloat("webcam_height", cropPreferences.getFloat("webcam_size", 0.31f)).coerceIn(0.05f, 0.95f)) }
    val timingPreferences = remember(context) { context.getSharedPreferences("smart_recorder_timing", android.content.Context.MODE_PRIVATE) }
    var recordingMinutes by remember(timingPreferences) { mutableFloatStateOf(timingPreferences.getInt("recording_minutes", 10).toFloat().coerceIn(1f, 240f)) }
    val mainCropWidth = 0.31640625f
    var mainX by remember(cropPreferences) {
        mutableFloatStateOf(cropPreferences.getFloat("main_x", 0.5f).coerceIn(0f, 1f - mainCropWidth))
    }
    var webcamX by remember(cropPreferences) {
        mutableFloatStateOf(cropPreferences.getFloat("webcam_x", 0.58f).coerceIn(0f, 1f - webcamWidth))
    }
    var webcamY by remember(cropPreferences) {
        mutableFloatStateOf(cropPreferences.getFloat("webcam_y", 0.12f).coerceIn(0f, 1f - webcamHeight))
    }
    var regionSaved by remember(cropPreferences) {
        mutableStateOf(cropPreferences.contains("main_x") && cropPreferences.contains("webcam_x") &&
            cropPreferences.contains("webcam_y") && cropPreferences.contains("webcam_width") &&
            cropPreferences.contains("webcam_height"))
    }
    var captureRunning by remember { mutableStateOf(false) }
    val projectionManager = remember { context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    val capturePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val captureIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_MAIN_X, mainX)
                putExtra(ScreenCaptureService.EXTRA_MAIN_Y, 0f)
                putExtra(ScreenCaptureService.EXTRA_MAIN_WIDTH, mainCropWidth)
                putExtra(ScreenCaptureService.EXTRA_MAIN_HEIGHT, 1f)
                putExtra(ScreenCaptureService.EXTRA_CROP_X, webcamX)
                putExtra(ScreenCaptureService.EXTRA_CROP_Y, webcamY)
                putExtra(ScreenCaptureService.EXTRA_CROP_WIDTH, webcamWidth)
                putExtra(ScreenCaptureService.EXTRA_CROP_HEIGHT, webcamHeight)
                putExtra(ScreenCaptureService.EXTRA_DURATION_MS, recordingMinutes.toLong() * 60_000L)
            }
            context.startForegroundService(captureIntent)
            captureRunning = true
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        capturePermission.launch(projectionManager.createScreenCaptureIntent())
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF238BCE)))),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Page, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SMART RECORDER", color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Text("Central de captura", color = TextMuted, fontSize = 12.sp)
                }
                Row(Modifier.clip(CircleShape).background(Color(0xFF18372F)).padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
                    Spacer(Modifier.width(6.dp))
                    Text("CAPTURA LOCAL", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Column {
                Text("Transforme sua live em conteúdo vertical.", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                Spacer(Modifier.height(5.dp))
                Text("Posicione separadamente o vídeo vertical 9:16 e a webcam. O app grava as duas regiões em arquivos independentes, sem salvar um vídeo da tela inteira.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFF24334A))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FONTE DE VÍDEO", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Text("YouTube ao vivo", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.clip(RoundedCornerShape(9.dp)).background(PanelSoft).padding(9.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Accent)
                        }
                    }
                    Text("Abra a transmissão no YouTube. Quando o Android mostrar as opções de captura, escolha a janela do YouTube se essa opção estiver disponível; assim você evita gravar notificações e outras telas. A captura de janela depende da versão do Android.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    Button(onClick = {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                        if (launchIntent != null) context.startActivity(launchIntent)
                        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
                    }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Page)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ABRIR YOUTUBE", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, if (captureRunning) Accent else Color(0xFF24334A))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("CAPTURA DA TELA", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(if (captureRunning) "Captura em andamento" else "Pronto para capturar", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("O Android solicitará autorização para captura de tela e microfone. O áudio será captado pelo microfone do aparelho (som reproduzido no ambiente), não como áudio digital interno do YouTube.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    Button(
                        onClick = {
                            if (captureRunning) {
                                context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                captureRunning = false
                            } else {
                                if (Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (captureRunning) Color(0xFFE35D6A) else Accent, contentColor = Page)
                    ) {
                        Text(if (captureRunning) "PARAR CAPTURA" else "INICIAR CAPTURA", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DUAS ÁREAS DE CAPTURA", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Azul: vídeo vertical 9:16 • Verde: webcam. Arraste cada quadro separadamente.", color = TextMuted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.Tune, contentDescription = null, tint = Accent)
                }
                PreviewSelector(mainX, webcamX, webcamY, webcamWidth, webcamHeight,
                    onMainChange = { x -> mainX = x; regionSaved = false },
                    onWebcamChange = { x, y, w, h ->
                        webcamX = x; webcamY = y; webcamWidth = w; webcamHeight = h; regionSaved = false
                    })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Largura da região", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${(webcamWidth * 100).toInt()}%", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(value = webcamWidth, onValueChange = { webcamWidth = it.coerceIn(0.05f, 0.95f); webcamX = webcamX.coerceIn(0f, 1f - webcamWidth); regionSaved = false }, valueRange = 0.05f..0.95f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Altura da região", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${(webcamHeight * 100).toInt()}%", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(value = webcamHeight, onValueChange = { webcamHeight = it.coerceIn(0.05f, 0.95f); webcamY = webcamY.coerceIn(0f, 1f - webcamHeight); regionSaved = false }, valueRange = 0.05f..0.95f)
                OutlinedButton(onClick = {
                    cropPreferences.edit()
                        .putFloat("main_x", mainX)
                        .putFloat("webcam_x", webcamX)
                        .putFloat("webcam_y", webcamY)
                        .putFloat("webcam_width", webcamWidth)
                        .putFloat("webcam_height", webcamHeight)
                        .putFloat("webcam_size", minOf(webcamWidth, webcamHeight))
                        .apply()
                    regionSaved = true
                }, modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, if (regionSaved) Accent else Color(0xFF34445D))) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = if (regionSaved) Accent else TextMain)
                    Spacer(Modifier.width(8.dp))
                    Text(if (regionSaved) "REGIÃO DEFINIDA" else "CONFIRMAR REGIÃO",
                        color = if (regionSaved) Accent else TextMain, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF26364D))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DURAÇÃO DA GRAVAÇÃO", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("A captura será encerrada automaticamente após o tempo escolhido.", color = TextMuted, fontSize = 12.sp)
                    Text("${recordingMinutes.toInt()} minutos", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Slider(value = recordingMinutes, onValueChange = { recordingMinutes = it }, valueRange = 1f..240f, steps = 238)
                    OutlinedButton(onClick = { timingPreferences.edit().putInt("recording_minutes", recordingMinutes.toInt()).apply() }, modifier = Modifier.fillMaxWidth()) { Text("SALVAR DURAÇÃO", color = Accent) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("FORMATO DE SAÍDA", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutputTile("9:16", "Vertical", Modifier.weight(1f))
                    OutputTile("1:1", "Webcam", Modifier.weight(1f))
                    OutputTile("9:16+", "Combinado", Modifier.weight(1f))
                }
                Text("O app grava diretamente as duas regiões selecionadas e gera três MP4: vídeo vertical, webcam separada e composição com a webcam sobreposta. Não é criado arquivo de tela inteira.",
                    color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun PreviewSelector(
    mainX: Float, webcamX: Float, webcamY: Float, webcamWidth: Float, webcamHeight: Float,
    onMainChange: (Float) -> Unit,
    onWebcamChange: (Float, Float, Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val currentMainX by rememberUpdatedState(mainX)
    val currentWebcamX by rememberUpdatedState(webcamX)
    val currentWebcamY by rememberUpdatedState(webcamY)
    val currentWebcamWidth by rememberUpdatedState(webcamWidth)
    val currentWebcamHeight by rememberUpdatedState(webcamHeight)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF17243A), Color(0xFF080D18))))
            .border(1.dp, Color(0xFF2B3C55), RoundedCornerShape(18.dp))
    ) {
        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF52637D), modifier = Modifier.size(36.dp))
                Text("ÁREA DE REFERÊNCIA 16:9", color = Color(0xFF687B97), fontSize = 9.sp)
            }
        }
        Box(
            modifier = Modifier.offset(x = maxWidth * mainX, y = 0.dp)
                .width(maxWidth * 0.31640625f).height(maxHeight)
                .border(2.dp, Color(0xFF4D9DFF), RoundedCornerShape(8.dp))
                .background(Color(0x222E82E6))
                .pointerInput(maxWidth, maxHeight) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val pw = with(density) { maxWidth.toPx() }
                        onMainChange((currentMainX + drag.x / pw).coerceIn(0f, 1f - 0.31640625f))
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Text("VÍDEO 9:16", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp).background(Color(0xCC174C91), RoundedCornerShape(5.dp)).padding(5.dp))
        }
        Box(
            modifier = Modifier.offset(x = maxWidth * webcamX, y = maxHeight * webcamY)
                .width(maxWidth * webcamWidth).height(maxHeight * webcamHeight)
                .border(2.dp, Accent, RoundedCornerShape(7.dp)).background(Color(0x553ED8B5))
                .pointerInput(maxWidth, maxHeight) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val pw = with(density) { maxWidth.toPx() }
                        val ph = with(density) { maxHeight.toPx() }
                        val nextX = (currentWebcamX + drag.x / pw)
                            .coerceIn(0f, 1f - currentWebcamWidth)
                        val nextY = (currentWebcamY + drag.y / ph)
                            .coerceIn(0f, 1f - currentWebcamHeight)
                        onWebcamChange(nextX, nextY, currentWebcamWidth, currentWebcamHeight)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMain, modifier = Modifier.size(19.dp))
                Text("WEBCAM", color = TextMain, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text("↘", color = Page, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomEnd)
                .clip(CircleShape).background(Accent).padding(horizontal = 4.dp))
        }
        Text("ARRASTE PARA MOVER • AJUSTE LARGURA E ALTURA ABAIXO", color = Accent, fontSize = 7.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC08111D)).padding(4.dp))
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
