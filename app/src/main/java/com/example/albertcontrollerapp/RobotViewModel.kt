package com.example.albertcontrollerapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.albertcontrollerapp.ui.BtDevice
import com.example.albertcontrollerapp.ui.ConnectionState
import com.example.albertcontrollerapp.ui.Direction
import com.example.albertcontrollerapp.ui.RobotUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.core.content.edit
import kotlin.time.Duration.Companion.milliseconds

/** Tiene insieme collegamento, comandi e dati del robot. L'interfaccia legge solo questi stati. */
class RobotViewModel(app: Application) : AndroidViewModel(app) {

    private val link = PicoLink(app)
    private val prefs = app.getSharedPreferences("albert", Application.MODE_PRIVATE)

    /** Ultimo dispositivo scelto dall'utente (ricordato tra un avvio e l'altro). */
    var savedDevice by mutableStateOf(loadDevice()); private set
    var pickerVisible by mutableStateOf(false); private set
    var pairedDevices by mutableStateOf<List<BtDevice>>(emptyList()); private set

    var connection by mutableStateOf(ConnectionState.Idle); private set
    var robot by mutableStateOf(RobotUiState()); private set
    var errorMessage by mutableStateOf<String?>(null); private set

    private val pressed = mutableSetOf<Direction>()
    private var speed = 0                       // 0..MAX_SPEED, dallo slider
    private var driveJob: Job? = null
    private var lastDrive: String? = null

    init {
        viewModelScope.launch { link.events.collect { onEvent(it) } }
    }

    /* ---------- collegamento ---------- */

    /** Si collega al dispositivo scelto; se non ne hai ancora scelto uno apre l'elenco. */
    fun connect() {
        val device = savedDevice
        if (device == null) { openPicker(); return }
        if (connection == ConnectionState.Connecting) return
        connection = ConnectionState.Connecting
        errorMessage = null
        log("Collegamento a ${device.name}…")
        viewModelScope.launch {
            link.connect(viewModelScope, device.address)
                .onSuccess {
                    connection = ConnectionState.Connected
                    log("Collegato a ${device.name}")
                }
                .onFailure {
                    connection = ConnectionState.Failed
                    errorMessage = it.message
                    log("Errore: ${it.message}")
                }
        }
    }

    /* ---------- scelta del dispositivo ---------- */

    fun openPicker() {
        pairedDevices = link.pairedDevices()
        pickerVisible = true
    }

    fun closePicker() { pickerVisible = false }

    fun selectDevice(device: BtDevice) {
        prefs.edit { putString("mac", device.address).putString("name", device.name) }
        savedDevice = device
        pickerVisible = false
        connect()
    }

    private fun loadDevice(): BtDevice? {
        val mac = prefs.getString("mac", null) ?: return null
        return BtDevice(prefs.getString("name", mac) ?: mac, mac)
    }

    fun reportError(message: String) {
        connection = ConnectionState.Failed
        errorMessage = message
    }

    private fun onEvent(ev: LinkEvent) {
        when (ev) {
            is LinkEvent.Line ->
                if (ev.text.startsWith("T ")) parseTelemetry(ev.text) else log("RX ${ev.text}")
            is LinkEvent.Closed -> {
                stopDriving()
                connection = ConnectionState.Failed
                errorMessage = "Collegamento perso: ${ev.reason}"
                log("Collegamento perso: ${ev.reason}")
            }
        }
    }

    /** T batteria_mV sx cx dx direzione velocità emergenza modo encSx encDx */
    private fun parseTelemetry(line: String) {
        val f = line.trim().split(" ")
        if (f.size < 11) return
        fun num(i: Int, old: Int) = f[i].toFloatOrNull()?.toInt() ?: old
        robot = robot.copy(
            batteryMv = num(1, robot.batteryMv),
            leftCm = num(2, robot.leftCm),
            centerCm = num(3, robot.centerCm),
            rightCm = num(4, robot.rightCm),
            directionDeg = num(5, robot.directionDeg),
            speedPercent = num(6, robot.speedPercent),
            emergencyPressed = f[7] == "1",
            status = when (f[8].toIntOrNull()) {
                1 -> "In movimento"
                2 -> "Arresto"
                else -> "Fermo"
            },
        )
    }

    /* ---------- comandi ---------- */

    fun setSpeed(percent: Int) { speed = percent }

    /** Pulsante premuto (true) o rilasciato (false). Finché è premuto il comando viene ripetuto. */
    fun onMove(direction: Direction, isPressed: Boolean) {
        if (connection != ConnectionState.Connected && isPressed) return
        if (isPressed) pressed += direction else pressed -= direction

        if (pressed.isEmpty()) {
            stopDriving()
            sendCommand("V 0 0")
        } else {
            sendDrive()
            if (driveJob == null) {
                driveJob = viewModelScope.launch {
                    while (isActive) {
                        delay(PicoConfig.COMMAND_REPEAT_MS.milliseconds)
                        sendDrive()
                    }
                }
            }
        }
    }

    private fun sendDrive() {
        val v = (if (Direction.Forward in pressed) 1 else 0) - (if (Direction.Backward in pressed) 1 else 0)
        val w = (if (Direction.Right in pressed) 1 else 0) - (if (Direction.Left in pressed) 1 else 0)
        val k = speed / 100.0
        val left = (v + w) * k
        val right = (v - w) * k
        val m = maxOf(1.0, abs(left), abs(right))
        val line = "V ${(left / m * 100).toInt().coerceIn(-100, 100)} ${(right / m * 100).toInt().coerceIn(-100, 100)}"
        sendCommand(line, logIt = line != lastDrive)
        lastDrive = line
    }

    private fun stopDriving() {
        driveJob?.cancel(); driveJob = null
        pressed.clear()
        lastDrive = null
    }

    fun stop() {
        stopDriving()
        sendCommand("V 0 0")
        sendCommand("S")
    }

    fun rearm() = sendCommand("R")

    private fun sendCommand(line: String, logIt: Boolean = true) {
        link.send(line)
        if (logIt) log("TX $line")
    }

    /* ---------- console ---------- */

    fun togglePause() { robot = robot.copy(consolePaused = !robot.consolePaused) }
    fun clearConsole() { robot = robot.copy(consoleLines = emptyList()) }

    private fun log(text: String) {
        robot = robot.copy(consoleLines = (robot.consoleLines + text).takeLast(300))
    }

    override fun onCleared() {
        link.shutdown("V 0 0", "S")
    }
}
