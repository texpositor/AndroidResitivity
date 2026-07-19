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

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()

            socket?.let {
                reader = BufferedReader(InputStreamReader(it.inputStream))
                writer = PrintWriter(it.outputStream, true)
            }

            Log.d(TAG, "Connected to ${device.name}")
            return@withContext true
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            close()
            return@withContext false
        }
    }

    fun readChannels() {
        if (!isReadingChannel.compareAndSet(false, true)) {
            return // Already reading
        }
        currentReadSequence = 1
    }

    suspend fun startDataLoop() = withContext(Dispatchers.IO) {
        val currentWriter = writer ?: return@withContext
        val currentReader = reader ?: return@withContext

        while (socket?.isConnected == true) {
            try {
                val activeChannel = currentReadSequence

                if (activeChannel in 1..8) {
                    // Send READ command for current channel
                    currentWriter.println("READ$activeChannel")
                    currentWriter.flush()
                    _logs.emit("Sent: READ$activeChannel")

                    // Read responses until we get END for this channel
                    var channelDone = false
                    while (!channelDone && socket?.isConnected == true) {
                        // Check if data is available before reading
                        if (currentReader.ready()) {
                            val line = currentReader.readLine()
                            if (line != null && line.isNotBlank()) {
                                if (line.startsWith("END:")) {
                                    _logs.emit(line)
                                    channelDone = true
                                } else {
                                    processLine(line)
                                }
                            }
                        } else {
                            // No data available, send JSON to check for updates
                            currentWriter.println("JSON")
                            currentWriter.flush()
                            delay(100)
                        }
                    }

                    if (activeChannel < 8) {
                        currentReadSequence++
                    } else {
                        currentReadSequence = 0 // Complete
                        isReadingChannel.set(false)
                        _logs.emit("Channel scan complete.")
                    }
                } else {
                    // Normal idle polling - get JSON updates
                    currentWriter.println("JSON")
                    currentWriter.flush()

                    // Read any available data
                    while (currentReader.ready()) {
                        val line = currentReader.readLine()
                        if (line != null && line.isNotBlank()) {
                            processLine(line)
                        }
                    }
                    delay(500)
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
            _measurements.emit(data)
        } catch (ignored: Exception) {
            // Not JSON, emit as log
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
    }
}