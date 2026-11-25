package com.example.albertcontrollerapp


import com.example.albertcontrollerapp.ui.theme.*

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat


/* ============================ MODELLI ============================ */

enum class ConnectionState { Idle, Connecting, Connected, Failed }

enum class Direction { Forward, Backward, Left, Right }

/** Tutto quello che l'interfaccia mostra: la tua logica aggiorna questo oggetto. */
data class RobotUiState(
    val status: String = "Fermo",
    val speedPercent: Int = 0,
    val directionDeg: Int = 0,
    val emergencyPressed: Boolean = false,
    val leftCm: Int = 0,
    val centerCm: Int = 0,
    val rightCm: Int = 0,
    val consoleLines: List<String> = emptyList(),
    val consolePaused: Boolean = false,
    val cameraFrame: ImageBitmap? = null,   // ultimo frame della telecamera
)

/** Limite massimo della velocità impostabile dallo slider (0..MAX_SPEED %). */
const val MAX_SPEED = 60
/** Distanza massima mostrata dalle barre (cm). */
const val MAX_DISTANCE_CM = 100

/* ============================ ROOT ============================ */

@Composable
fun AlbertApp(
    connection: ConnectionState,
    robot: RobotUiState,
    onConnect: () -> Unit,
    onMove: (Direction, Boolean) -> Unit,   // (direzione, premuto?)
    onSpeedChange: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onClearConsole: () -> Unit,
) {
    AlbertTheme {
        Surface(Modifier.fillMaxSize(), color = AlbertBg) {
            when (connection) {
                ConnectionState.Idle, ConnectionState.Connecting ->
                    StartScreen(connecting = connection == ConnectionState.Connecting, onConnect = onConnect)
                ConnectionState.Failed -> FailedScreen(onRetry = onConnect)
                ConnectionState.Connected -> ConnectedScreen(
                    robot, onMove, onSpeedChange, onTogglePause, onClearConsole,
                )
            }
        }
    }
}

/* ============================ INIZIO ============================ */

@Composable
fun StartScreen(connecting: Boolean, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Cominciamo con Albert!",
            fontSize = 36.sp, fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center, color = AlbertText,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onConnect,
            enabled = !connecting,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AlbertCyan, contentColor = AlbertOnCyan,
                disabledContainerColor = AlbertPanel2, disabledContentColor = AlbertMute,
            ),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
        ) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(20.dp), color = AlbertCyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Connessione…", fontWeight = FontWeight.Bold)
            } else {
                Text("Connettiti", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

/* ============================ ERRORE ============================ */

@Composable
fun FailedScreen(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.baseline_bluetooth_disabled_24), contentDescription = null,
            modifier = Modifier.size(96.dp), tint = AlbertText,
        )
        Spacer(Modifier.height(24.dp))
        Text("Connessione non riuscita", fontSize = 18.sp, color = AlbertText)
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AlbertPanel2, contentColor = AlbertText),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
        ) { Text("Riprova", fontWeight = FontWeight.SemiBold) }
    }
}

/* ============================ TAB ============================ */

@Composable
fun ConnectedScreen(
    robot: RobotUiState,
    onMove: (Direction, Boolean) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onClearConsole: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = tab != 0) { tab = 0 }

    if (tab == 2) {
        // Tab 3: schermo intero in orizzontale
        ControlScreen(robot, onMove, onSpeedChange, onExit = { tab = 0 })
        return
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = AlbertPanel,
            contentColor = AlbertText,
        ) {
            listOf("Stato", "Console", "Controllo").forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(title, fontWeight = FontWeight.SemiBold) },
                    selectedContentColor = AlbertText,
                    unselectedContentColor = AlbertMute,
                )
            }
        }
        when (tab) {
            0 -> StatusTab(robot)
            1 -> ConsoleTab(robot, onTogglePause, onClearConsole)
        }
    }
}

/* ---------- Tab 1: Stato ---------- */

@Composable
fun StatusTab(r: RobotUiState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        InfoRow("Stato", r.status)
        InfoRow("Velocità", "${r.speedPercent}%")
        InfoRow("Direzione", "${r.directionDeg}°")
        InfoRow(
            "Pulsante di Emergenza Fisico",
            if (r.emergencyPressed) "Premuto" else "Libero",
            valueColor = if (r.emergencyPressed) AlbertRed else AlbertText,
        )
        DistanceRow("Sinistra", r.leftCm)
        DistanceRow("Centro", r.centerCm)
        DistanceRow("Destra", r.rightCm)
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = AlbertText) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AlbertMute, fontSize = 16.sp)
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DistanceRow(label: String, cm: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AlbertMute, fontSize = 16.sp, modifier = Modifier.width(80.dp))
        LinearProgressIndicator(
            progress = { (cm.toFloat() / MAX_DISTANCE_CM).coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
            color = if (cm < 20) AlbertAmber else AlbertCyan,
            trackColor = AlbertPanel,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            "${cm}cm", color = AlbertText, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

/* ---------- Tab 2: Console ---------- */

@Composable
fun ConsoleTab(r: RobotUiState, onTogglePause: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Console", color = AlbertText, fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp, modifier = Modifier.weight(1f),
            )
            SoftButton(if (r.consolePaused) "Riprendi" else "Pause", onTogglePause, Modifier.weight(1f))
            SoftButton("Clean", onClear, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        val listState = rememberLazyListState()
        LaunchedEffect(r.consoleLines.size, r.consolePaused) {
            if (!r.consolePaused && r.consoleLines.isNotEmpty()) {
                listState.scrollToItem(r.consoleLines.lastIndex)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(AlbertConsoleBg)
                .padding(8.dp),
        ) {
            items(r.consoleLines) { line ->
                Text(line, color = AlbertText, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AlbertPanel2, contentColor = AlbertText),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/* ---------- Tab 3: Camera + controlli (orizzontale, schermo intero) ---------- */

@Composable
fun ControlScreen(
    r: RobotUiState,
    onMove: (Direction, Boolean) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onExit: () -> Unit,
) {
    LandscapeFullscreenEffect()

    var speed by rememberSaveable { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize().background(AlbertConsoleBg)) {
        // Telecamera a tutta finestra
        val frame = r.cameraFrame
        if (frame != null) {
            Image(
                bitmap = frame, contentDescription = "Telecamera del robot",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                "In attesa del video…", color = AlbertMute,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Pulsante per tornare ai tab
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AlbertPanel.copy(alpha = 0.7f)),
        ) { Icon(painterResource(R.drawable.baseline_arrow_back_24), contentDescription = "Indietro", tint = AlbertText) }

        // Croce direzionale (in basso a sinistra)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoldButton(R.drawable.baseline_arrow_upward_24, "Avanti") { onMove(Direction.Forward, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton(R.drawable.baseline_arrow_back_24, "Sinistra") { onMove(Direction.Left, it) }
                Spacer(Modifier.size(72.dp))
                HoldButton(R.drawable.baseline_arrow_forward_24, "Destra") { onMove(Direction.Right, it) }
            }
            HoldButton(R.drawable.baseline_arrow_downward_24, "Indietro") { onMove(Direction.Backward, it) }
        }

        // Slider velocità limitato a MAX_SPEED (in basso a destra)
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(24.dp)
                .width(300.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AlbertPanel.copy(alpha = 0.75f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Velocità", color = AlbertText, fontSize = 15.sp)
            Slider(
                value = speed,
                onValueChange = { speed = it; onSpeedChange(it.toInt()) },
                valueRange = 0f..MAX_SPEED.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AlbertCyan,
                    activeTrackColor = AlbertCyan,
                    inactiveTrackColor = AlbertLine,
                ),
            )
            Text("${speed.toInt()}%", color = AlbertText, fontSize = 15.sp, modifier = Modifier.width(40.dp))
        }
    }
}

/** Pulsante a pressione mantenuta: onHold(true) alla pressione, onHold(false) al rilascio. */
@Composable
private fun HoldButton(@DrawableRes icon: Int, description: String, onHold: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnHold by rememberUpdatedState(onHold)

    // Se il pulsante esce dallo schermo mentre è premuto, invia il rilascio
    DisposableEffect(Unit) { onDispose { if (pressed) currentOnHold(false) } }

    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (pressed) AlbertCyan else AlbertPanel2.copy(alpha = 0.85f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    currentOnHold(true)
                    waitForUpOrCancellation()
                    pressed = false
                    currentOnHold(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription = description, modifier = Modifier.size(36.dp),
            tint = if (pressed) AlbertOnCyan else AlbertText,
        )
    }
}

/* ============================ UTILITÀ ============================ */

/** Forza l'orizzontale e nasconde le barre di sistema finché è nella composizione. */
@Composable
private fun LandscapeFullscreenEffect() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
