package team.night.canlink

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import java.util.concurrent.TimeUnit

class SignalRManager {

    companion object {
        private const val TAG = "SignalRManager"
        private const val RECONNECT_DELAY_MS = 5000L
    }

    private var hubConnection: HubConnection? = null
    private var serverUrl: String = ""
    private var shouldReconnect = false
    private val mainHandler = Handler(Looper.getMainLooper())

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onReconnecting: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun connect(serverUrl: String) {
        this.serverUrl = serverUrl
        shouldReconnect = true
        doConnect()
    }

    private fun doConnect() {
        if (!shouldReconnect) return

        try {
            // Clean up old connection
            hubConnection?.stop()
            hubConnection = null

            hubConnection = HubConnectionBuilder.create("$serverUrl/vehiclehub")
                .build()

            hubConnection?.on("Connected", { connectionId: String ->
                Log.d(TAG, "Connected with ID: $connectionId")
                mainHandler.post { onConnected?.invoke() }
            }, String::class.java)

            hubConnection?.onClosed { exception ->
                Log.d(TAG, "Connection closed: ${exception?.message}")
                mainHandler.post { onDisconnected?.invoke() }

                // Auto-reconnect
                if (shouldReconnect) {
                    mainHandler.post { onReconnecting?.invoke() }
                    scheduleReconnect()
                }
            }

            hubConnection?.start()?.blockingAwait(30, TimeUnit.SECONDS)
            Log.d(TAG, "SignalR connected to $serverUrl")

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            mainHandler.post { onError?.invoke("Connection failed: ${e.message}") }

            // Auto-reconnect on failure
            if (shouldReconnect) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        Log.d(TAG, "Scheduling reconnect in ${RECONNECT_DELAY_MS}ms")
        Thread {
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
                if (shouldReconnect) {
                    Log.d(TAG, "Attempting reconnect...")
                    doConnect()
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Reconnect interrupted")
            }
        }.start()
    }

    fun sendVehicleData(
        deviceId: String,
        speed: Int,
        speedUnit: String,
        rpm: Int,
        temperature: Int,
        tempUnit: String
    ) {
        if (hubConnection?.connectionState != HubConnectionState.CONNECTED) {
            Log.w(TAG, "Not connected, cannot send data")
            return
        }

        try {
            val data = hashMapOf(
                "deviceId" to deviceId,
                "speed" to speed,
                "speedUnit" to speedUnit,
                "rpm" to rpm,
                "temperature" to temperature,
                "tempUnit" to tempUnit
            )

            hubConnection?.send("SendVehicleData", data)
            Log.d(TAG, "Sent: Speed=$speed, RPM=$rpm, Temp=$temperature")

        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            mainHandler.post { onError?.invoke("Failed to send data: ${e.message}") }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        try {
            hubConnection?.stop()?.blockingAwait(5, TimeUnit.SECONDS)
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
        hubConnection = null
    }

    fun isConnected(): Boolean {
        return hubConnection?.connectionState == HubConnectionState.CONNECTED
    }
}
