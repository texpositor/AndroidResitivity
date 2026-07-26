package me.speleologist.resistivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val bluetoothService = BluetoothService()

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
            bluetoothService.startDataLoop()

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

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}