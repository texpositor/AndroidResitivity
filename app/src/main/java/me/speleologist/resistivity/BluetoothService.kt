package me.speleologist.resistivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

class BluetoothService {
    private val TAG = "BluetoothService"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    
    private var socket: BluetoothSocket? = null
    private val _measurements = MutableSharedFlow<MeasurementData>()
    val measurements: SharedFlow<MeasurementData> = _measurements

    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()
            Log.d(TAG, "Connected to ${device.name}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            close()
            false
        }
    }

    suspend fun sendRunCommand() = withContext(Dispatchers.IO) {
        try {
            socket?.outputStream?.let {
                it.write("RUN\n".toByteArray())
                it.flush()
                Log.d(TAG, "Sent: RUN")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send RUN command", e)
        }
    }

    suspend fun startDataLoop() = withContext(Dispatchers.IO) {
        val currentSocket = socket ?: return@withContext
        val outputStream = currentSocket.outputStream
        val reader = BufferedReader(InputStreamReader(currentSocket.inputStream))

        while (currentSocket.isConnected) {
            try {
                // Send "JSON" request command
                outputStream.write("JSON\n".toByteArray())
                outputStream.flush()

                // Read response line-by-line
                val line = reader.readLine()
                if (line != null && line.isNotBlank()) {
                    Log.d(TAG, "Received: $line")
                    try {
                        val data = json.decodeFromString<MeasurementData>(line)
                        _measurements.emit(data)
                    } catch (e: Exception) {
                        Log.e(TAG, "JSON parsing failed for: $line", e)
                    }
                }
                delay(1000) // Request every second
            } catch (e: IOException) {
                Log.e(TAG, "Data loop error", e)
                break
            }
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not close socket", e)
        }
        socket = null
    }
}
