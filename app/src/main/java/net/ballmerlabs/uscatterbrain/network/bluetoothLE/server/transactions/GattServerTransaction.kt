package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

data class GattServerTransaction<T>(
        val first: T,
        val second: ServerResponseTransaction
)