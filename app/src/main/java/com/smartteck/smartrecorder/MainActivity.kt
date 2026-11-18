package com.smartteck.smartrecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    var webcamX by remember { mutableFloatStateOf(0.58f) }
    var webcamY by remember { mutableFloatStateOf(0.12f) }
    var webcamSize by remember { mutableFloatStateOf(0.31f) }
    var regionSaved by remember { mutableStateOf(false) }

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
                    Text("PRONTO", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Column {
                Text("Sua transmissão, do seu jeito.", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                Spacer(Modifier.height(5.dp))
                Text("Configure a área da webcam e prepare o enquadramento vertical.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
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
                    Text("Abra a transmissão no aplicativo oficial do YouTube. O SMART RECORDER não usa um player intermediário.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ÁREA DE ENQUADRAMENTO", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Arraste o quadrado até a webcam", color = TextMuted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.Tune, contentDescription = null, tint = Accent)
                }
                PreviewSelector(webcamX, webcamY, webcamSize) { x, y ->
                    webcamX = x; webcamY = y; regionSaved = false
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamanho da seleção", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${(webcamSize * 100).toInt()}%", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(value = webcamSize, onValueChange = {
                    webcamSize = it.coerceIn(0.20f, 0.48f); regionSaved = false
                }, valueRange = 0.20f..0.48f)
                OutlinedButton(onClick = { regionSaved = true }, modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, if (regionSaved) Accent else Color(0xFF34445D))) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = if (regionSaved) Accent else TextMain)
                    Spacer(Modifier.width(8.dp))
                    Text(if (regionSaved) "REGIÃO DEFINIDA" else "CONFIRMAR REGIÃO",
                        color = if (regionSaved) Accent else TextMain, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("FORMATO DE SAÍDA", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutputTile("9:16", "Vídeo vertical", Modifier.weight(1f))
                    OutputTile("1:1", "Área da webcam", Modifier.weight(1f))
                }
                Text("Prévia de interface: os controles de gravação e processamento serão conectados às permissões e serviços nativos nas próximas etapas.",
                    color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun PreviewSelector(webcamX: Float, webcamY: Float, webcamSize: Float, onPositionChange: (Float, Float) -> Unit) {
    val currentWebcamX by rememberUpdatedState(webcamX)
    val currentWebcamY by rememberUpdatedState(webcamY)
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF17243A), Color(0xFF080D18))))
            .border(1.dp, Color(0xFF2B3C55), RoundedCornerShape(18.dp))
    ) {
        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF52637D), modifier = Modifier.size(36.dp))
                Text("PRÉVIA DA TRANSMISSÃO", color = Color(0xFF687B97), fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
        Box(
            modifier = Modifier.offset(x = maxWidth * webcamX, y = maxHeight * webcamY)
                .size(width = maxWidth * webcamSize, height = maxHeight * webcamSize)
                .border(2.dp, Accent, RoundedCornerShape(8.dp))
                .background(Color(0x553ED8B5))
                .pointerInput(maxWidth, maxHeight, webcamSize) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val parentWidth = size.width / webcamSize
                        val parentHeight = size.height / webcamSize
                        val nextX = (currentWebcamX + dragAmount.x / parentWidth).coerceIn(0f, 1f - webcamSize)
                        val nextY = (currentWebcamY + dragAmount.y / parentHeight).coerceIn(0f, 1f - webcamSize)
                        currentOnPositionChange(nextX, nextY)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMain, modifier = Modifier.size(19.dp))
                Text("WEBCAM", color = TextMain, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.align(Alignment.TopStart).padding(5.dp).size(5.dp).clip(CircleShape).background(Accent))
        }
        Text("MOVER", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                .clip(RoundedCornerShape(6.dp)).background(Color(0xCC08111D)).padding(horizontal = 7.dp, vertical = 4.dp))
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
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Seus vídeos", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Os vídeos reenquadrados aparecerão nesta área.", color = TextMuted, fontSize = 13.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(12.dp))
                Text("Nenhum vídeo por enquanto", color = TextMain, fontWeight = FontWeight.SemiBold)
                Text("Quando o processamento estiver integrado, seus arquivos serão organizados aqui.", color = TextMuted, fontSize = 12.sp)
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
                Text("Captura nativa", color = TextMain, fontWeight = FontWeight.SemiBold)
                Text("A gravação e o processamento serão executados no dispositivo, sem servidor externo.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
