package me.speleologist.resistivity

import kotlinx.serialization.Serializable

@Serializable
data class MeasurementData(
    val channel: Int,
    val voltage: Double,
    val current_ma: Double,
    val direction: String,
    val stabilized: Boolean,
    val average_voltage: Double
)
