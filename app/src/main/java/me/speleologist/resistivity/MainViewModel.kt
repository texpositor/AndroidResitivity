package me.speleologist.resistivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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

    data class UiState(
        val isConnected: Boolean = false,
        val latestData: MeasurementData? = null,
        val pairedDevices: List<BluetoothDevice> = emptyList(),
        val errorMessage: String? = null
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
                startDataCollection()
            } else {
                _uiState.value = _uiState.value.copy(errorMessage = "Connection failed")
            }
        }
    }

    fun runCommand() {
        viewModelScope.launch {
            bluetoothService.sendRunCommand()
        }
    }

    private fun startDataCollection() {
        dataJob?.cancel()
        dataJob = viewModelScope.launch {
            launch {
                bluetoothService.measurements.collect { data ->
                    _uiState.value = _uiState.value.copy(latestData = data)
                }
            }
            bluetoothService.startDataLoop()
        }
    }

    fun disconnect() {
        dataJob?.cancel()
        bluetoothService.close()
        _uiState.value = _uiState.value.copy(isConnected = false, latestData = null)
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
