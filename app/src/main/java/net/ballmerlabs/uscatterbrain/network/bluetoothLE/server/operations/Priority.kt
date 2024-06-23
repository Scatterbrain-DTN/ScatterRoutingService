package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

enum class Priority(val priority: Int) {
    HIGH(100),
    NORMAL(50),
    LOW(0)
}