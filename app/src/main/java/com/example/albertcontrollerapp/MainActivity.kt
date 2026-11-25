package com.example.albertcontrollerapp



import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.albertcontrollerapp.ui.AlbertApp

class MainActivity : ComponentActivity() {

    private val vm: RobotViewModel by viewModels()
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) afterPermission?.invoke()
            else vm.reportError("Serve il permesso Bluetooth per collegarsi al Pico")
            afterPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlbertApp(
                connection = vm.connection,
                robot = vm.robot,
                errorMessage = vm.errorMessage,
                onConnect = { withBluetoothPermission { vm.connect() } },
                onMove = vm::onMove,
                onSpeedChange = vm::setSpeed,
                onTogglePause = vm::togglePause,
                onClearConsole = vm::clearConsole,
                onStop = vm::stop,
                onRearm = vm::rearm,
                deviceName = vm.savedDevice?.name,
                onPickDevice = { withBluetoothPermission { vm.openPicker() } },
                pickerVisible = vm.pickerVisible,
                pairedDevices = vm.pairedDevices,
                onDeviceSelected = vm::selectDevice,
                onDismissPicker = vm::closePicker,
                onOpenBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
        }
    }

    /** Esegue l'azione solo con il permesso Bluetooth (richiesto da Android 12 in poi). */
    private fun withBluetoothPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}