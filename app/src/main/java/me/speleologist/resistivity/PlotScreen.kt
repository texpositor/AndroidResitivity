package me.speleologist.resistivity

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollState
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.FloatEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlotScreen(
    viewModel: MainViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val points by viewModel.resistivityPoints.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Apparent Resistivity") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.clearPlotData()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear Plot")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (points.isEmpty()) {
                Text(
                    text = "No data yet.\nRun measurements to build the sounding curve.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // Build chart model
                val model = remember(points) {
                    val entries = points.mapIndexed { index, p ->
                        FloatEntry(index.toFloat(), p.rho.toFloat())
                    }
                    entryModelOf(entries)
                }
                val xValues = remember(points) { points.map { it.x.toFloat() } }

                Chart(
                    chart = lineChart(),
                    model = model,
                    startAxis = rememberStartAxis(
                        title = "ρa (Ωm)"
                    ),
                    bottomAxis = rememberBottomAxis(
                        title = "AB/2 (m)",
                        valueFormatter = { value: Float, _ ->
                            val idx = value.toInt()
                            if (idx in xValues.indices) xValues[idx].toString() else ""
                        }
                    ),
                    chartScrollState = rememberChartScrollState()
                )
            }
        }
    }
}