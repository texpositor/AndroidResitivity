package me.speleologist.resistivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResistivityDashboard(
    viewModel: MainViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Settings dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }
    val mn by viewModel.mnSpacing.collectAsState()
    val d by viewModel.electrodeSpacing.collectAsState()
    val offset by viewModel.offset.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Resistivity Controller") },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { navController.navigate("plot") }) {
                        Icon(Icons.Default.ShowChart, contentDescription = "Plot")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
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
                    onPowerCommand = { isOn, channel -> viewModel.sendManualPowerCommand(isOn, channel) },
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

    // ---- Settings Dialog ----
    if (showSettingsDialog) {
        var mnText by remember { mutableStateOf(mn.toString()) }
        var dText by remember { mutableStateOf(d.toString()) }
        var offsetText by remember { mutableStateOf(offset.toString()) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Electrode Settings") },
            text = {
                Column {
                    OutlinedTextField(
                        value = mnText,
                        onValueChange = { mnText = it },
                        label = { Text("MN spacing (m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dText,
                        onValueChange = { dText = it },
                        label = { Text("Electrode spacing d (m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = { offsetText = it },
                        label = { Text("Offset (m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newMn = mnText.toFloatOrNull() ?: 2f
                        val newD = dText.toFloatOrNull() ?: 2f
                        val newOffset = offsetText.toFloatOrNull() ?: 1f
                        scope.launch {
                            viewModel.updateSettings(newMn, newD, newOffset)
                        }
                        showSettingsDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// (The rest of the file – DeviceList, MeasurementDisplay, LogList, DataCard – stays exactly as you have it.)
// I'll include them for completeness but they are unchanged.

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
    onPowerCommand: (Boolean, String) -> Unit,
    onDisconnect: () -> Unit,
    onRun: () -> Unit
) {
    var manualChannelText by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Channel selector (unchanged)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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

        // Live Data Header & Buttons (unchanged)
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

        // Measurement Data Section (unchanged)
        Column(modifier = Modifier.fillMaxWidth()) {
            if (data == null) {
                Text(
                    text = "Connected, press Run",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
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

            Spacer(modifier = Modifier.height(2.dp))

            // Manual Power Controls (unchanged)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CH # ",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        BasicTextField(
                            value = manualChannelText,
                            onValueChange = { manualChannelText = it },
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            if (manualChannelText.isNotBlank()) {
                                onPowerCommand(true, manualChannelText)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("ON", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            if (manualChannelText.isNotBlank()) {
                                onPowerCommand(false, manualChannelText)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("OFF", fontSize = 12.sp)
                    }
                }
            }
        }

        // Log Section (unchanged)
        Text("Log", style = MaterialTheme.typography.titleMedium)
        LogList(logs = logs, modifier = Modifier.weight(1f))
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
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                value,
                fontWeight = FontWeight.Bold,
                color = color,
                fontSize = 14.sp
            )
        }
    }
}