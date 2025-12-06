package team.night.canlink

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import team.night.canlink.databinding.ActivityMainBinding
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "team.night.canlink.USB_PERMISSION"
        private const val PREFS_NAME = "canlink_prefs"
        private const val PREF_USE_MILES = "use_miles"
        private const val PREF_USE_FAHRENHEIT = "use_fahrenheit"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var canableManager: CANableManager

    private var pollingTimer: Timer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Unit preferences
    private var useMiles = false
    private var useFahrenheit = false

    // Last received values (in metric)
    private var lastSpeedKmh: Int? = null
    private var lastTempC: Int? = null

    // USB permission receiver
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    connectToDevice()
                } else {
                    showToast("USB permission denied")
                }
            }
        }
    }

    // USB disconnect receiver
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                canableManager.disconnect()
                updateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        canableManager = CANableManager(this)
        loadPreferences()
        setupCANableCallbacks()
        setupUI()
        registerReceivers()

        // Set version
        val versionName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }
        binding.tvVersion.text = "v.$versionName"

        // Check if launched by USB attach
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            connectToDevice()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        canableManager.disconnect()
        unregisterReceivers()
    }

    private fun setupCANableCallbacks() {
        canableManager.setDebugMode(true)

        canableManager.onSpeedReceived = { speed ->
            mainHandler.post {
                lastSpeedKmh = speed
                updateSpeedDisplay()
            }
        }

        canableManager.onRpmReceived = { rpm ->
            mainHandler.post {
                binding.tvRpm.text = "$rpm"
            }
        }

        canableManager.onCoolantTempReceived = { temp ->
            mainHandler.post {
                lastTempC = temp
                updateTempDisplay()
            }
        }

        canableManager.onConnected = {
            mainHandler.post {
                updateUI()
                startPolling()
                showToast("Connected to CANable")
            }
        }

        canableManager.onDisconnected = {
            mainHandler.post {
                stopPolling()
                updateUI()
                showToast("Disconnected")
            }
        }

        canableManager.onError = { error ->
            mainHandler.post {
                showToast(error)
                appendLog("ERROR: $error")
            }
        }

        canableManager.onDebugMessage = { message ->
            mainHandler.post {
                appendLog(message)
            }
        }
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            if (canableManager.isConnected()) {
                canableManager.disconnect()
            } else {
                requestUsbPermission()
            }
        }

        binding.btnRequestSpeed.setOnClickListener {
            canableManager.requestSpeed()
        }

        binding.btnRequestRpm.setOnClickListener {
            canableManager.requestRpm()
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
        }

        // Toggle speed units (km/h <-> mph)
        binding.tvSpeedUnit.setOnClickListener {
            useMiles = !useMiles
            savePreferences()
            updateSpeedDisplay()
        }

        // Toggle temperature units (C <-> F)
        binding.tvTemp.setOnClickListener {
            useFahrenheit = !useFahrenheit
            savePreferences()
            updateTempDisplay()
        }

        updateUI()
        updateSpeedDisplay()
        updateTempDisplay()
    }

    private fun updateUI() {
        val connected = canableManager.isConnected()
        binding.btnConnect.text = if (connected) "Close" else "Open"
        binding.tvStatus.text = if (connected) "Connected" else "Disconnected"
        binding.tvStatus.setTextColor(
            getColor(if (connected) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        binding.btnRequestSpeed.isEnabled = connected
        binding.btnRequestRpm.isEnabled = connected
    }

    private fun requestUsbPermission() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val drivers = canableManager.findDevices()

        if (drivers.isEmpty()) {
            showToast("No CANable device found")
            return
        }

        val device = drivers.first().device

        if (usbManager.hasPermission(device)) {
            connectToDevice()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice() {
        canableManager.connect(
            baudRate = 115200,
            canSpeed = 500000
        )
    }

    private fun startPolling() {
        stopPolling()
        pollingTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (canableManager.isConnected()) {
                        canableManager.requestAllData()
                    }
                }
            }, 0, 500)  // Poll every 500ms
        }
    }

    private fun stopPolling() {
        pollingTimer?.cancel()
        pollingTimer = null
    }

    private fun appendLog(message: String) {
        val currentText = binding.tvLog.text.toString()
        val newText = if (currentText.length > 5000) {
            // Trim old log entries
            message + "\n" + currentText.take(4000)
        } else {
            message + "\n" + currentText
        }
        binding.tvLog.text = newText
    }

    private fun registerReceivers() {
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires explicit export flag
            registerReceiver(usbPermissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(usbDetachReceiver, detachFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, permissionFilter)
            registerReceiver(usbDetachReceiver, detachFilter)
        }
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(usbPermissionReceiver)
            unregisterReceiver(usbDetachReceiver)
        } catch (_: Exception) {}
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateSpeedDisplay() {
        val speed = lastSpeedKmh
        if (speed != null) {
            if (useMiles) {
                val mph = (speed * 0.621371).toInt()
                binding.tvSpeed.text = "$mph"
                binding.tvSpeedUnit.text = "mph"
            } else {
                binding.tvSpeed.text = "$speed"
                binding.tvSpeedUnit.text = "km/h"
            }
        } else {
            binding.tvSpeed.text = "---"
            binding.tvSpeedUnit.text = if (useMiles) "mph" else "km/h"
        }
    }

    private fun updateTempDisplay() {
        val temp = lastTempC
        if (temp != null) {
            if (useFahrenheit) {
                val fahrenheit = (temp * 9 / 5) + 32
                binding.tvTemp.text = "$fahrenheit°F"
            } else {
                binding.tvTemp.text = "$temp°C"
            }
        } else {
            binding.tvTemp.text = if (useFahrenheit) "---°F" else "---°C"
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        useMiles = prefs.getBoolean(PREF_USE_MILES, false)
        useFahrenheit = prefs.getBoolean(PREF_USE_FAHRENHEIT, false)
    }

    private fun savePreferences() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_USE_MILES, useMiles)
            .putBoolean(PREF_USE_FAHRENHEIT, useFahrenheit)
            .apply()
    }
}
