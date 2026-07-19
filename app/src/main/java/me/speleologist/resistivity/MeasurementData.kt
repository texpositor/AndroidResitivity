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

// Added command/response types
sealed class BluetoothCommand {
    data class ReadChannel(val channel: Int) : BluetoothCommand()
    object GetJson : BluetoothCommand()
    object Help : BluetoothCommand()
    object Status : BluetoothCommand()
    object AllOff : BluetoothCommand()
    data class SetTolerance(val value: Float) : BluetoothCommand()
}

data class CommandResponse(
    val sequence: Int,
    val type: ResponseType,
    val data: String
)

enum class ResponseType {
    DATA, END, ERROR, LOG
}