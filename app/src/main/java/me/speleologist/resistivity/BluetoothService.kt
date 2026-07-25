package me.speleologist.resistivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothService {
    private val TAG = "BluetoothService"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null

    private val _measurements = MutableSharedFlow<MeasurementData>()
    val measurements: SharedFlow<MeasurementData> = _measurements

    private val _logs = MutableSharedFlow<String>()
    val logs: SharedFlow<String> = _logs

    private val json = Json { ignoreUnknownKeys = true }

    // Command state
    private val isReadingChannel = AtomicBoolean(false)
    private var currentReadSequence = 0
    private var initialJsonSent = false // Add this flag

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()

            socket?.let {
                reader = BufferedReader(InputStreamReader(it.inputStream))
                writer = PrintWriter(it.outputStream, true)
            }

            // Reset the flag on new connection
            initialJsonSent = false

            Log.d(TAG, "Connected to ${device.name}")
            return@withContext true
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            close()
            return@withContext false
        }
    }

    fun sendInitialJsonCommand() {
        if (!initialJsonSent) {
            val currentWriter = writer ?: return
            currentWriter.println("JSON")
            currentWriter.flush()
            initialJsonSent = true
            Log.d(TAG, "Sent initial JSON command")
        }
    }

    fun readChannels() {
        currentReadSequence = if (currentReadSequence >= 8) 1 else currentReadSequence + 1

        val currentWriter = writer ?: return
        currentWriter.println("READ$currentReadSequence")
        currentWriter.flush()
    }

    suspend fun startDataLoop() = withContext(Dispatchers.IO) {
        val currentReader = reader ?: return@withContext

        while (socket?.isConnected == true) {
            try {
                val line = currentReader.readLine()
                if (line != null && line.isNotBlank()) {
                    processLine(line)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Data loop error", e)
                _logs.emit("Error: ${e.message}")
                break
            }
        }
    }

    private suspend fun processLine(line: String) {
        try {
            val data = json.decodeFromString<MeasurementData>(line)
            _logs.emit("${data.type}: ${data.msg}")
            if (data.type == "DATA") {
                _measurements.emit(data)
            }
        } catch (ignored: Exception) {
            _logs.emit(line)
        }
    }

    fun close() {
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not close socket", e)
        }
        socket = null
        writer = null
        reader = null
        isReadingChannel.set(false)
        currentReadSequence = 0
        initialJsonSent = false // Reset flag
    }
}