package me.speleologist.resistivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ResistivityDashboard(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (!state.isConnected) {
            Text("Select a Device", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            DeviceList(devices = state.pairedDevices) { device ->
                viewModel.connectToDevice(device)
            }
        } else {
            MeasurementDisplay(
                data = state.latestData,
                onDisconnect = { viewModel.disconnect() },
                onRun = { viewModel.runCommand() }
            )
        }

        state.errorMessage?.let {
            LaunchedEffect(it) {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceList(devices: List<BluetoothDevice>, onDeviceClick: (BluetoothDevice) -> Unit) {
    LazyColumn {
        items(devices) { device ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onDeviceClick(device) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = device.name ?: "Unknown Device", fontWeight = FontWeight.Bold)
                    Text(text = device.address, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun MeasurementDisplay(data: MeasurementData?, onDisconnect: () -> Unit, onRun: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Live Data", style = MaterialTheme.typography.headlineMedium)
            Row {
                Button(onClick = onRun, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                    Text("RUN")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (data == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            DataCard(label = "Channel", value = data.channel.toString())
            DataCard(label = "Voltage", value = "%.4f V".format(data.voltage))
            DataCard(label = "Current", value = "%.2f mA".format(data.current_ma))
            DataCard(label = "Direction", value = data.direction)
            DataCard(label = "Average Voltage", value = "%.4f V".format(data.average_voltage))
            DataCard(
                label = "Stabilized",
                value = if (data.stabilized) "Yes" else "No",
                color = if (data.stabilized) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        }
    }
}

@Composable
fun DataCard(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 18.sp)
        }
    }
}
