package com.example.albertcontrollerapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.albertcontrollerapp.ui.BtDevice
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

/** Impostazioni del collegamento al Pico 2 W: cambia solo questi valori. */
object PicoConfig {
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val CONNECT_ATTEMPTS = 3

    /** Se il Pico non manda nulla per questo tempo, il collegamento è considerato perso. 0 = disattivato. */
    const val SILENCE_TIMEOUT_MS = 0

    /** Ogni quanto ripetere il comando di guida mentre un pulsante è premuto. */
    const val COMMAND_REPEAT_MS = 100L
}

sealed interface LinkEvent {
    data class Line(val text: String) : LinkEvent
    data class Closed(val reason: String) : LinkEvent
}

/**
 * Collegamento Bluetooth Classic (SPP / RFCOMM) al Pico 2 W.
 * Protocollo a righe di testo: invii "V 30 30", "S", "R"; ricevi "T ..." (telemetria) e altre righe.
 */
class PicoLink(private val context: Context) {

    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    private val alive = AtomicBoolean(false)
    private val lastRx = AtomicLong(0)
    private var socket: BluetoothSocket? = null
    private var outbox: Channel<String>? = null
    private val jobs = mutableListOf<Job>()

    /** Trova il Pico e si collega. Riprova da solo più volte prima di arrendersi. */
    @SuppressLint("MissingPermission")
    suspend fun connect(scope: CoroutineScope, address: String): Result<Unit> = withContext(Dispatchers.IO) {
        close()
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return@withContext Result.failure(IOException("Questo telefono non ha il Bluetooth"))
            if (!adapter.isEnabled) {
                return@withContext Result.failure(IOException("Attiva il Bluetooth e riprova"))
            }
            val device = try {
                adapter.getRemoteDevice(address.uppercase())
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(IOException("Indirizzo Bluetooth non valido"))
            }

            var lastError: String? = null
            repeat(PicoConfig.CONNECT_ATTEMPTS) {
                try {
                    val s = device.createRfcommSocketToServiceRecord(PicoConfig.SPP_UUID)
                    try {
                        s.connect()
                    } catch (e: IOException) {
                        runCatching { s.close() }
                        throw e
                    }
                    socket = s
                    start(scope, s)
                    return@withContext Result.success(Unit)
                } catch (e: IOException) {
                    lastError = e.message
                    delay(700.milliseconds)
                }
            }
            Result.failure(IOException("Connessione al Pico non riuscita (${lastError ?: "timeout"})"))
        } catch (e: SecurityException) {
            Result.failure(IOException("Permesso Bluetooth negato"))
        }
    }

    /** Dispositivi Bluetooth già associati al telefono (serve il permesso BLUETOOTH_CONNECT). */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BtDevice> = try {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter?.bondedDevices.orEmpty()
            .map { BtDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun start(scope: CoroutineScope, s: BluetoothSocket) {
        alive.set(true)
        lastRx.set(System.currentTimeMillis())
        val ch = Channel<String>(Channel.UNLIMITED)
        outbox = ch
        val out = s.outputStream
        val input = s.inputStream.bufferedReader()

        jobs += scope.launch(Dispatchers.IO) {          // scrittura, in ordine
            try {
                for (line in ch) {
                    out.write((line + "\n").toByteArray())
                    out.flush()
                }
            } catch (e: IOException) {
                fail("scrittura interrotta")
            }
        }
        jobs += scope.launch(Dispatchers.IO) {          // lettura
            try {
                while (true) {
                    val line = input.readLine() ?: break
                    lastRx.set(System.currentTimeMillis())
                    _events.tryEmit(LinkEvent.Line(line.trim()))
                }
                fail("collegamento chiuso dal Pico")
            } catch (e: IOException) {
                fail("collegamento perso")
            }
        }
        if (PicoConfig.SILENCE_TIMEOUT_MS > 0) {        // controllo che il Pico sia ancora vivo
            jobs += scope.launch {
                while (isActive) {
                    delay(1000.milliseconds)
                    if (System.currentTimeMillis() - lastRx.get() > PicoConfig.SILENCE_TIMEOUT_MS) {
                        fail("il Pico non risponde")
                        break
                    }
                }
            }
        }
    }

    /** Accoda una riga da inviare. Restituisce false se non sei collegato. */
    fun send(line: String): Boolean = outbox?.trySend(line)?.isSuccess == true

    private fun fail(reason: String) {
        if (alive.compareAndSet(true, false)) {
            cleanup()
            _events.tryEmit(LinkEvent.Closed(reason))
        }
    }

    /** Chiusura voluta: non genera l'evento Closed. */
    fun close() {
        alive.set(false)
        cleanup()
    }

    /** Invia gli ultimi comandi (es. fermo) e poi chiude, anche se l'app sta per chiudersi. */
    fun shutdown(vararg lastLines: String) {
        val out = runCatching { socket?.outputStream }.getOrNull()
        alive.set(false)
        thread {
            runCatching {
                lastLines.forEach { out?.write((it + "\n").toByteArray()) }
                out?.flush()
            }
            cleanup()
        }
    }

    private fun cleanup() {
        outbox?.close(); outbox = null
        jobs.forEach { it.cancel() }; jobs.clear()
        runCatching { socket?.close() }; socket = null
    }
}
