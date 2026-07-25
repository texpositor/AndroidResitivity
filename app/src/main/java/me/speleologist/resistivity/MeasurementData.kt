package me.speleologist.resistivity

import kotlinx.serialization.Serializable

@Serializable
data class MeasurementData(
    val type: String = "DATA",
    val msg: String = "",
    val channel: Int = 0,
    val voltage: Double = 0.0,
    val current_ma: Double = 0.0,
    val direction: String = "FORWARD",
    val stabilized: Boolean = false,
    val average_voltage: Double = 0.0
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