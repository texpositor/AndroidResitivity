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
import androidx.compose.ui.text.font.FontFamily
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
                logs = state.logs,
                selectedChannel = state.selectedChannel,
                onChannelSelected = { viewModel.setSelectedChannel(it) },
                onDisconnect = { viewModel.disconnect() },
                onRun = { viewModel.readChannels() }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onDeviceClick(device) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = device.name ?: "Unknown Device",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun MeasurementDisplay(
    data: MeasurementData?,
    logs: List<String>,
    selectedChannel: Int,
    onChannelSelected: (Int) -> Unit,
    onDisconnect: () -> Unit,
    onRun: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        // --- 1. Compact 2-Row Channel Selector ---
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Row 1: CH 1 to 4
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (1..4).forEach { channel ->
                        FilterChip(
                            selected = (channel == selectedChannel),
                            onClick = { onChannelSelected(channel) },
                            label = {
                                Text(
                                    text = "CH $channel",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                // Row 2: CH 5 to 8
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (5..8).forEach { channel ->
                        FilterChip(
                            selected = (channel == selectedChannel),
                            onClick = { onChannelSelected(channel) },
                            label = {
                                Text(
                                    text = "CH $channel",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 2. Live Data Header & Action Buttons (Under the Chips) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Live Data", style = MaterialTheme.typography.headlineMedium)
            Row {
                Button(
                    onClick = onRun,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("RUN")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 3. Measurement Data Section ---
        Column(modifier = Modifier.weight(0.6f)) {
            if (data == null) {
                Text(
                    text = "Connected, press Run",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DataCard(label = "Voltage", value = "--- V")
                DataCard(label = "Current", value = "--- mA")
                DataCard(label = "Direction", value = "---")
                DataCard(label = "Average Voltage", value = "--- V")
                DataCard(
                    label = "Stabilized",
                    value = "---",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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

        Spacer(modifier = Modifier.height(8.dp))

        // --- 4. Log Section ---
        Text("Log", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        LogList(logs = logs, modifier = Modifier.weight(0.4f))
    }
}

@Composable
fun LogList(logs: List<String>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        LazyColumn(
            modifier = Modifier.padding(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(logs) { log ->
                Text(
                    text = log,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun DataCard(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                value,
                fontWeight = FontWeight.Bold,
                color = color,
                fontSize = 16.sp
            )
        }
    }
}