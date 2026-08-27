package me.speleologist.resistivity

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI

private val Context.dataStore by preferencesDataStore("settings")

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bluetoothService = BluetoothService()
    private val fileUriCache = mutableMapOf<String, Uri>()   // for MediaStore logging (unchanged)

    // ---- UI state (existing) ----
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // ---- Settings (new) ----
    private val dataStore = application.dataStore
    private val MN_KEY = floatPreferencesKey("mn_spacing")
    private val D_KEY = floatPreferencesKey("electrode_spacing")
    private val OFFSET_KEY = floatPreferencesKey("offset")

    private val _mnSpacing = MutableStateFlow(2f)
    val mnSpacing: StateFlow<Float> = _mnSpacing.asStateFlow()

    private val _electrodeSpacing = MutableStateFlow(2f)
    val electrodeSpacing: StateFlow<Float> = _electrodeSpacing.asStateFlow()

    private val _offset = MutableStateFlow(1f)
    val offset: StateFlow<Float> = _offset.asStateFlow()

    // ---- Plot data (new) ----
    private val _resistivityPoints = MutableStateFlow<List<ResistivityPoint>>(emptyList())
    val resistivityPoints: StateFlow<List<ResistivityPoint>> = _resistivityPoints.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    // ---- Existing state ----
    private var dataJob: Job? = null
    private var initialCommandSent = false

    data class UiState(
        val isConnected: Boolean = false,
        val latestData: MeasurementData? = null,
        val pairedDevices: List<BluetoothDevice> = emptyList(),
        val logs: List<String> = emptyList(),
        val errorMessage: String? = null,
        val selectedChannel: Int = 1
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Load settings from DataStore
            dataStore.data.collect { preferences ->
                _mnSpacing.value = preferences[MN_KEY] ?: 2f
                _electrodeSpacing.value = preferences[D_KEY] ?: 2f
                _offset.value = preferences[OFFSET_KEY] ?: 1f
            }
        }
        // Load plot data from internal storage
        viewModelScope.launch(Dispatchers.IO) {
            loadPlotData()
        }
    }

    // ---- Settings update ----
    suspend fun updateSettings(mn: Float, d: Float, offset: Float) {
        dataStore.edit { prefs ->
            prefs[MN_KEY] = mn
            prefs[D_KEY] = d
            prefs[OFFSET_KEY] = offset
        }
        // The flows will update via the dataStore.collect above
    }

    // ---- Plot data persistence ----
    private suspend fun loadPlotData() {
        val file = getApplication<Application>().filesDir.resolve("plot_data.json")
        if (file.exists()) {
            try {
                val jsonStr = file.readText()
                val points = json.decodeFromString<List<ResistivityPoint>>(jsonStr)
                _resistivityPoints.value = points
                Log.d("Resistivity", "Loaded ${points.size} plot points")
            } catch (e: Exception) {
                Log.e("Resistivity", "Failed to load plot data", e)
            }
        }
    }

    private suspend fun savePlotData() {
        val file = getApplication<Application>().filesDir.resolve("plot_data.json")
        try {
            val jsonStr = json.encodeToString(_resistivityPoints.value)
            file.writeText(jsonStr)
        } catch (e: Exception) {
            Log.e("Resistivity", "Failed to save plot data", e)
        }
    }

    fun clearPlotData() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = getApplication<Application>().filesDir.resolve("plot_data.json")
            file.delete()
            _resistivityPoints.value = emptyList()
        }
    }

    private suspend fun addResistivityPoint(point: ResistivityPoint) {
        val current = _resistivityPoints.value.toMutableList()
        current.add(point)
        _resistivityPoints.value = current
        savePlotData()
        // Also emit a log line
        emitLog("CH${point.channel}: ρa = %.2f Ωm at AB/2 = %.2f m".format(point.rho, point.x))
    }

    // Helper to emit logs (used inside viewModelScope)
    private suspend fun emitLog(msg: String) {
        val currentLogs = _uiState.value.logs.toMutableList()
        currentLogs.add(0, msg)
        if (currentLogs.size > 50) currentLogs.removeAt(50)
        _uiState.value = _uiState.value.copy(logs = currentLogs)
    }

    // ---- Existing methods (unchanged except minor additions) ----
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
                initialCommandSent = false
                startDataCollection()
            } else {
                _uiState.value = _uiState.value.copy(errorMessage = "Connection failed")
            }
        }
    }

    fun setSelectedChannel(channel: Int) {
        _uiState.value = _uiState.value.copy(selectedChannel = channel)
    }

    fun sendManualPowerCommand(isOn: Boolean, channel: String) {
        if (channel.isNotBlank()) {
            val prefix = if (isOn) "ON" else "OFF"
            bluetoothService.sendCommand("$prefix$channel")
        }
    }

    fun readChannels() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
        bluetoothService.readChannel(_uiState.value.selectedChannel)
    }

    private fun startDataCollection() {
        dataJob?.cancel()
        dataJob = viewModelScope.launch {
            // 1. Collect measurements – update UI and compute resistivity
            launch {
                bluetoothService.measurements.collect { data ->
                    // Update latest data
                    _uiState.value = _uiState.value.copy(latestData = data)

                    // Handle END to auto-advance channel (existing behaviour)
                    if (data.type == "END") {
                        val nextChannel = if (_uiState.value.selectedChannel >= 8) 1 else _uiState.value.selectedChannel + 1
                        _uiState.value = _uiState.value.copy(selectedChannel = nextChannel)
                    }

                    // NEW: If DATA, compute apparent resistivity and add to plot
                    if (data.type == "DATA") {
                        val channel = data.channel
                        val voltage = data.voltage
                        val currentMa = data.current_ma
                        val currentA = currentMa / 1000.0

                        if (currentA != 0.0 && voltage != 0.0) {
                            val mn = _mnSpacing.value
                            val d = _electrodeSpacing.value
                            val offset = _offset.value

                            val abHalf = offset + channel * d  // half AB spacing
                            val mnHalf = mn / 2f

                            val rho = PI * ((abHalf * abHalf) - (mnHalf * mnHalf)) / mn * (voltage / currentA)
                            val point = ResistivityPoint(
                                x = abHalf.toDouble(),
                                rho = rho,
                                channel = channel,
                                voltage = voltage,
                                currentMa = currentMa
                            )
                            addResistivityPoint(point)
                        }
                    }
                }
            }

            // 2. Collect logs (unchanged)
            launch {
                bluetoothService.logs.collect { log ->
                    val currentLogs = _uiState.value.logs.toMutableList()
                    currentLogs.add(0, log)
                    if (currentLogs.size > 50) {
                        currentLogs.removeAt(50)
                    }
                    _uiState.value = _uiState.value.copy(logs = currentLogs)
                }
            }

            // 3. Start data loop (unchanged)
            launch {
                bluetoothService.startDataLoop()
            }

            // 4. File logging (unchanged – we keep the raw JSON logging)
            launch(Dispatchers.IO) {
                val dataRegex = Regex("\"type\"\\s*:\\s*\"DATA\"", RegexOption.IGNORE_CASE)
                bluetoothService.rawMessages.collect { line ->
                    saveToFile("res_log.json", line)
                    if (line.contains(dataRegex)) {
                        saveToFile("res_data.json", line)
                    }
                }
            }

            // 5. Send initial JSON command once (unchanged)
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

    // ---- File saving ----
    private fun saveToFile(fileName: String, data: String) {
        val context = getApplication<Application>()
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/ResistivityData"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentUri = MediaStore.Files.getContentUri("external")

                var fileUri = fileUriCache[fileName]

                if (fileUri == null) {
                    // 1. Query MediaStore to check if the file already exists
                    val projection = arrayOf(MediaStore.MediaColumns._ID)
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                    val selectionArgs = arrayOf(fileName, "$relativePath/")

                    resolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                            val id = cursor.getLong(idColumn)
                            fileUri = ContentUris.withAppendedId(contentUri, id)
                        }
                    }

                    // 2. If it doesn't exist, insert a new record
                    if (fileUri == null) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
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
                    resolver.openOutputStream(fileUri!!, "wa")?.use { output ->
                        output.write((data + "\n").toByteArray())
                    }
                    Log.d("Resistivity", "Successfully appended data to: $fileName")
                } else {
                    Log.e("Resistivity", "Failed to retrieve or create file URI")
                }
            } catch (e: Exception) {
                Log.e("Resistivity", "Failed to save via MediaStore", e)
            }
        } else {
            // Legacy fallback for Android 9 and below
            try {
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val customDir = File(publicDir, "ResistivityData")
                if (!customDir.exists()) {
                    customDir.mkdirs()
                }
                val file = File(customDir, fileName)
                FileOutputStream(file, true).use { output ->
                    output.write((data + "\n").toByteArray())
                }
                Log.d("Resistivity", "Saved to legacy public directory: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("Resistivity", "Failed legacy save to $fileName", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}