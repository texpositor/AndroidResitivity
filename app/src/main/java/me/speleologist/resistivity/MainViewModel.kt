package me.speleologist.resistivity

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore


class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bluetoothService = BluetoothService()
    private val fileUriCache = mutableMapOf<String, Uri>()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private var dataJob: Job? = null
    private var initialCommandSent = false // Add this flag

    data class UiState(
        val isConnected: Boolean = false,
        val latestData: MeasurementData? = null,
        val pairedDevices: List<BluetoothDevice> = emptyList(),
        val logs: List<String> = emptyList(),
        val errorMessage: String? = null,
        val selectedChannel: Int = 1
    )

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val devices = adapter?.bondedDevices?.toList() ?: emptyList()
        _uiState.value = _uiState.value.copy(pairedDevices = devices)
    }

    fun connectToDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(errorMessage = null)
            val success = bluetoothService.connect(device)
            if (success) {
                _uiState.value = _uiState.value.copy(isConnected = true)
                initialCommandSent = false // Reset flag
                startDataCollection()
            } else {
                _uiState.value = _uiState.value.copy(errorMessage = "Connection failed")
            }
        }
    }

    fun setSelectedChannel(channel: Int) {
        _uiState.value = _uiState.value.copy(selectedChannel = channel)
    }

    fun readChannels() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
        bluetoothService.readChannel(_uiState.value.selectedChannel)
    }

    private fun startDataCollection() {
        dataJob?.cancel()
        dataJob = viewModelScope.launch {
            launch {
                bluetoothService.measurements.collect { data ->
                    _uiState.value = _uiState.value.copy(latestData = data)
                    if (data.type == "END") {
                        val nextChannel = if (_uiState.value.selectedChannel >= 8) 1 else _uiState.value.selectedChannel + 1
                        _uiState.value = _uiState.value.copy(selectedChannel = nextChannel)
                    }
                }
            }
            launch {
                bluetoothService.logs.collect { log ->
                    val currentLogs = _uiState.value.logs.toMutableList()
                    currentLogs.add(0, log) // Add to top
                    if (currentLogs.size > 50) {
                        currentLogs.removeAt(50)
                    }
                    _uiState.value = _uiState.value.copy(logs = currentLogs)
                }
            }
            // Start the data loop
            launch {
                bluetoothService.startDataLoop()
            }

            // File logging
            launch(Dispatchers.IO) {
                val dataRegex = Regex("\"type\"\\s*:\\s*\"DATA\"", RegexOption.IGNORE_CASE)
                bluetoothService.rawMessages.collect { line ->
                    saveToFile("res_log.json", line)
                    if (line.contains(dataRegex)) {
                        saveToFile("res_data.json", line)
                    }
                }
            }

            // Send initial JSON command only once after connection
            if (!initialCommandSent) {
                bluetoothService.sendInitialJsonCommand()
                initialCommandSent = true
            }
        }
    }

    fun disconnect() {
        dataJob?.cancel()
        bluetoothService.close()
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            latestData = null,
            logs = emptyList()
        )
        initialCommandSent = false
    }

    private fun saveToFile(fileName: String, data: String) {
        val context = getApplication<Application>()
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/ResistivityData"

        try {
            val resolver = context.contentResolver
            val contentUri = MediaStore.Files.getContentUri("external")

            var fileUri = fileUriCache[fileName]

            if (fileUri == null) {
                // 1. Query MediaStore to check if the file already exists
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                // Note: The relative path string in MediaStore must end with a trailing slash "/"
                val selectionArgs = arrayOf(fileName, "$relativePath/")

                resolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        // File exists, retrieve its unique ID and build its URI
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val id = cursor.getLong(idColumn)
                        fileUri = ContentUris.withAppendedId(contentUri, id)
                    }
                }

                // 2. If it doesn't exist, insert a new record
                if (fileUri == null) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    }
                    fileUri = resolver.insert(contentUri, contentValues)
                }

                if (fileUri != null) {
                    fileUriCache[fileName] = fileUri!!
                }
            }

            // 3. Append data to the file URI
            if (fileUri != null) {
                // "wa" mode opens the stream for writing and appends to existing content
                resolver.openOutputStream(fileUri, "wa")?.use { output ->
                    output.write((data + "\n").toByteArray())
                }
                Log.d("Resistivity", "Successfully appended data to: $fileName")
            } else {
                Log.e("Resistivity", "Failed to retrieve or create file URI")
            }
        } catch (e: Exception) {
            Log.e("Resistivity", "Failed to save via MediaStore", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}