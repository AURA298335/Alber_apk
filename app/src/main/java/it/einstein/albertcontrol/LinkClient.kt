package it.einstein.albertcontrol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

sealed class LinkEvent {
    data object Connected : LinkEvent()
    data class Disconnected(val reason: String) : LinkEvent()
    data class Line(val text: String) : LinkEvent()
}

class LinkClient(private val context: Context, private val listener: (LinkEvent) -> Unit) {
    private var socket: Socket? = null
    private var btSocket: BluetoothSocket? = null
    private var output: OutputStream? = null
    @Volatile private var running = false

    fun connectWifi(ip: String, port: Int) {
        disconnect()
        running = true
        thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 5000)
                s.soTimeout = 3000
                socket = s
                output = s.getOutputStream()
                listener(LinkEvent.Connected)
                readLoop(BufferedReader(InputStreamReader(s.getInputStream())))
            } catch (e: Exception) {
                listener(LinkEvent.Disconnected(e.message ?: "errore Wi-Fi"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectBluetooth(mac: String) {
        disconnect()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter
            ?: run { listener(LinkEvent.Disconnected("Bluetooth non disponibile")); return }
        if (!adapter.isEnabled) { listener(LinkEvent.Disconnected("Bluetooth disattivato")); return }
        val device: BluetoothDevice = adapter.getRemoteDevice(mac)
        running = true
        thread {
            try {
                val s = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                adapter.cancelDiscovery()
                s.connect()
                btSocket = s
                output = s.outputStream
                listener(LinkEvent.Connected)
                readLoop(BufferedReader(InputStreamReader(s.inputStream)))
            } catch (e: Exception) {
                listener(LinkEvent.Disconnected(e.message ?: "errore Bluetooth"))
            }
        }
    }

    private fun readLoop(reader: BufferedReader) {
        try {
            while (running) {
                val line = reader.readLine() ?: break
                listener(LinkEvent.Line(line))
            }
            listener(LinkEvent.Disconnected("collegamento chiuso"))
        } catch (e: Exception) {
            if (running) listener(LinkEvent.Disconnected(e.message ?: "lettura interrotta"))
        }
    }

    fun send(line: String) {
        try {
            output?.write((line + "\n").toByteArray())
            output?.flush()
        } catch (_: Exception) {}
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        socket = null; btSocket = null; output = null
    }
}
