package it.einstein.albertcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var link: LinkClient
    private lateinit var status: TextView
    private lateinit var telemetry: TextView
    private lateinit var log: TextView
    private lateinit var speed: SeekBar
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        link = LinkClient(this) { ev -> runOnUiThread { onEvent(ev) } }

        status = findViewById(R.id.status)
        telemetry = findViewById(R.id.telemetry)
        log = findViewById(R.id.log)
        speed = findViewById(R.id.speed)

        findViewById<Button>(R.id.connectBtn).setOnClickListener { connect() }
        findViewById<Button>(R.id.disconnectBtn).setOnClickListener {
            link.disconnect(); setConnected(false, "Scollegato")
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { send("S") }
        findViewById<Button>(R.id.btnRearm).setOnClickListener { send("R") }
        findViewById<Button>(R.id.btnTalk).setOnClickListener { send("L 3") }

        bindHold(findViewById(R.id.btnFwd), 1, 0)
        bindHold(findViewById(R.id.btnBack), -1, 0)
        bindHold(findViewById(R.id.btnLeft), 0, -1)
        bindHold(findViewById(R.id.btnRight), 0, 1)

        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val useBt = findViewById<RadioButton>(R.id.radioBt).isChecked
        val address = findViewById<EditText>(R.id.address).text.toString().trim()
        if (address.isEmpty()) { toast("Inserisci un indirizzo"); return }
        if (useBt) {
            if (address.count { it == ':' } != 5) { toast("Inserisci un MAC Bluetooth valido"); return }
            link.connectBluetooth(address.uppercase())
        } else {
            val port = findViewById<EditText>(R.id.port).text.toString().trim().toIntOrNull()
            if (port == null) { toast("Inserisci la porta Wi-Fi"); return }
            link.connectWifi(address, port)
        }
        setConnected(false, "Connessione in corso…")
    }

    private fun bindHold(button: Button, v: Int, w: Int) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> drive(v, w)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> drive(0, 0)
            }
            true
        }
    }

    private fun drive(v: Int, w: Int) {
        val k = speed.progress / 100.0
        val left = (v + w) * k
        val right = (v - w) * k
        val m = maxOf(1.0, kotlin.math.abs(left), kotlin.math.abs(right))
        send("V ${(left / m * 100).toInt()} ${(right / m * 100).toInt()}")
    }

    private fun send(line: String) {
        if (!connected && line != "S") { toast("Prima collega il robot"); return }
        link.send(line)
        appendLog("TX $line")
    }

    private fun onEvent(ev: LinkEvent) {
        when (ev) {
            is LinkEvent.Connected -> setConnected(true, "Collegato")
            is LinkEvent.Disconnected -> setConnected(false, "Disconnesso: ${ev.reason}")
            is LinkEvent.Line -> {
                appendLog("RX ${ev.text}")
                if (ev.text.startsWith("T ")) telemetry.text = formatTelemetry(ev.text)
            }
        }
    }

    private fun formatTelemetry(line: String): String {
        val f = line.split(" ")
        if (f.size < 11) return line
        val bat = f[1].toIntOrNull() ?: 0
        val modes = arrayOf("fermo", "movimento", "arresto")
        val mode = (f[8].toIntOrNull() ?: 0).coerceIn(0, 2)
        return "Batteria: $bat mV\n" +
            "Sensori sx/centro/dx: ${f[2]} / ${f[3]} / ${f[4]} cm\n" +
            "Direzione: ${f[5]}°  Velocità: ${f[6]}\n" +
            "Emergenza fisica: ${if (f[7] == "1") "ATTIVA" else "no"}\n" +
            "Stato: ${modes[mode]}\n" +
            "Encoder sx/dx: ${f[9]} / ${f[10]}"
    }

    private fun setConnected(ok: Boolean, text: String) {
        connected = ok
        status.text = text
        status.setTextColor(if (ok) 0xFF4FD69C.toInt() else 0xFFFF5D5D.toInt())
    }

    private fun appendLog(text: String) {
        log.text = (text + "\n" + log.text).split("\n").take(12).joinToString("\n")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        link.send("V 0 0")
        link.send("S")
        link.disconnect()
    }
}
