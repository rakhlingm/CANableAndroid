package team.night.canlink

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors

/**
 * CANable OBD-II Manager
 * Handles USB serial communication with CANable device
 */
class CANableManager(private val context: Context) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "CANableManager"

        // OBD-II Constants
        private const val OBD_REQUEST_ID = 0x7DF  // Broadcast request ID
        private const val SERVICE_CURRENT_DATA = 0x01
        private const val PID_VEHICLE_SPEED = 0x0D
        private const val PID_ENGINE_RPM = 0x0C
        private const val PID_COOLANT_TEMP = 0x05

        // Default settings
        private const val DEFAULT_BAUD_RATE = 115200
        private const val DEFAULT_CAN_SPEED = 500000
    }

    // USB Serial
    private var usbSerialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null

    // Callbacks
    var onSpeedReceived: ((Int) -> Unit)? = null
    var onRpmReceived: ((Int) -> Unit)? = null
    var onCoolantTempReceived: ((Int) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDebugMessage: ((String) -> Unit)? = null

    // State
    private var isConnected = false
    private var debugMode = false

    // Buffer for incoming data
    private val receiveBuffer = StringBuilder()

    /**
     * Enable debug mode
     */
    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
    }

    /**
     * Find available CANable devices
     */
    fun findDevices(): List<UsbSerialDriver> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    /**
     * Connect to CANable device
     */
    fun connect(
        device: UsbDevice? = null,
        baudRate: Int = DEFAULT_BAUD_RATE,
        canSpeed: Int = DEFAULT_CAN_SPEED
    ): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Find driver
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            onError?.invoke("No USB serial devices found")
            return false
        }

        // Select device
        val driver = if (device != null) {
            drivers.find { it.device == device }
        } else {
            drivers.firstOrNull()
        }

        if (driver == null) {
            onError?.invoke("CANable device not found")
            return false
        }

        // Check permission
        if (!usbManager.hasPermission(driver.device)) {
            onError?.invoke("USB permission not granted")
            return false
        }

        try {
            // Open connection
            val connection = usbManager.openDevice(driver.device)
                ?: throw IOException("Failed to open USB device")

            usbSerialPort = driver.ports[0].apply {
                open(connection)
                setParameters(
                    baudRate,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
            }

            // Start reading
            serialIoManager = SerialInputOutputManager(usbSerialPort, this).apply {
                Executors.newSingleThreadExecutor().submit(this)
            }

            // Initialize CANable
            initializeCANable(canSpeed)

            isConnected = true
            onConnected?.invoke()
            debug("Connected to CANable at $baudRate baud, CAN speed: $canSpeed")

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            onError?.invoke("Connection failed: ${e.message}")
            disconnect()
            return false
        }
    }

    /**
     * Initialize CANable with SLCAN commands
     */
    private fun initializeCANable(canSpeed: Int) {
        // Close any existing connection
        sendCommand("C")
        Thread.sleep(100)

        // Set CAN baud rate
        val baudCmd = when (canSpeed) {
            10000 -> "S0"
            20000 -> "S1"
            50000 -> "S2"
            100000 -> "S3"
            125000 -> "S4"
            250000 -> "S5"
            500000 -> "S6"
            800000 -> "S7"
            1000000 -> "S8"
            else -> "S6"
        }
        sendCommand(baudCmd)
        Thread.sleep(100)

        // Open CAN channel
        sendCommand("O")
        Thread.sleep(100)
    }

    /**
     * Disconnect from CANable
     */
    fun disconnect() {
        try {
            sendCommand("C")  // Close CAN channel
        } catch (_: Exception) {}

        serialIoManager?.listener = null
        serialIoManager?.stop()
        serialIoManager = null

        try {
            usbSerialPort?.close()
        } catch (_: Exception) {}
        usbSerialPort = null

        isConnected = false
        onDisconnected?.invoke()
        debug("Disconnected")
    }

    /**
     * Request vehicle speed (PID 0x0D)
     */
    fun requestSpeed() {
        sendOBDRequest(PID_VEHICLE_SPEED)
    }

    /**
     * Request engine RPM (PID 0x0C)
     */
    fun requestRpm() {
        sendOBDRequest(PID_ENGINE_RPM)
    }

    /**
     * Request coolant temperature (PID 0x05)
     */
    fun requestCoolantTemp() {
        sendOBDRequest(PID_COOLANT_TEMP)
    }

    /**
     * Request all common PIDs
     */
    fun requestAllData() {
        requestSpeed()
        Thread.sleep(50)
        requestRpm()
        Thread.sleep(50)
        requestCoolantTemp()
    }

    /**
     * Send OBD-II request
     */
    private fun sendOBDRequest(pid: Int) {
        val data = byteArrayOf(
            0x02,
            SERVICE_CURRENT_DATA.toByte(),
            pid.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        sendStandardFrame(OBD_REQUEST_ID, data)
    }

    /**
     * Send standard CAN frame (11-bit ID)
     */
    private fun sendStandardFrame(canId: Int, data: ByteArray) {
        val frame = buildString {
            append("t")
            append(String.format("%03X", canId))
            append(String.format("%01X", data.size))
            data.forEach { append(String.format("%02X", it.toInt() and 0xFF)) }
        }
        sendCommand(frame)
    }

    /**
     * Send SLCAN command
     */
    private fun sendCommand(command: String) {
        debug("TX: $command")
        try {
            val data = (command + "\r").toByteArray()
            usbSerialPort?.write(data, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            onError?.invoke("Send failed: ${e.message}")
        }
    }

    /**
     * Handle received data from USB serial
     */
    override fun onNewData(data: ByteArray) {
        val received = String(data)
        receiveBuffer.append(received)

        // Process complete lines
        while (true) {
            val newlineIndex = receiveBuffer.indexOf('\r')
            if (newlineIndex < 0) break

            val line = receiveBuffer.substring(0, newlineIndex).trim()
            receiveBuffer.delete(0, newlineIndex + 1)

            if (line.isNotEmpty()) {
                debug("RX: $line")
                parseReceivedFrame(line)
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial error", e)
        onError?.invoke("Serial error: ${e.message}")
    }

    /**
     * Parse received CAN frame
     */
    private fun parseReceivedFrame(frame: String) {
        if (!frame.startsWith("t") || frame.length < 5) return

        try {
            val canId = frame.substring(1, 4).toInt(16)
            val dataLen = frame.substring(4, 5).toInt(16)

            if (frame.length < 5 + dataLen * 2) return

            val data = ByteArray(dataLen)
            for (i in 0 until dataLen) {
                data[i] = frame.substring(5 + i * 2, 7 + i * 2).toInt(16).toByte()
            }

            // Check if this is an OBD-II response (ECU response IDs: 0x7E8-0x7EF)
            if (canId in 0x7E8..0x7EF) {
                parseOBDResponse(data)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: $frame", e)
        }
    }

    /**
     * Parse OBD-II response
     */
    private fun parseOBDResponse(data: ByteArray) {
        if (data.size < 3) return

        val service = data[1].toInt() and 0xFF
        val pid = data[2].toInt() and 0xFF

        // Check for positive response (service + 0x40)
        if (service == SERVICE_CURRENT_DATA + 0x40) {
            when (pid) {
                PID_VEHICLE_SPEED -> {
                    if (data.size >= 4) {
                        val speed = data[3].toInt() and 0xFF
                        debug("Speed: $speed km/h")
                        onSpeedReceived?.invoke(speed)
                    }
                }

                PID_ENGINE_RPM -> {
                    if (data.size >= 5) {
                        val rpm = ((data[3].toInt() and 0xFF) * 256 + (data[4].toInt() and 0xFF)) / 4
                        debug("RPM: $rpm")
                        onRpmReceived?.invoke(rpm)
                    }
                }

                PID_COOLANT_TEMP -> {
                    if (data.size >= 4) {
                        val temp = (data[3].toInt() and 0xFF) - 40
                        debug("Coolant Temp: $temp C")
                        onCoolantTempReceived?.invoke(temp)
                    }
                }
            }
        }
    }

    private fun debug(message: String) {
        if (debugMode) {
            Log.d(TAG, message)
            onDebugMessage?.invoke(message)
        }
    }

    fun isConnected() = isConnected
}
