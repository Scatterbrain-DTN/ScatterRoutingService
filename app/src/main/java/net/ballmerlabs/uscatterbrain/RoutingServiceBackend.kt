package net.ballmerlabs.uscatterbrain

import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler

/**
 * Dagger2 interface for RoutingServiceBackend
 */
interface RoutingServiceBackend {
    object Applications {
        const val APPLICATION_FILESHARING = "fileshare"
    }
    val radioModule: BluetoothLEModule
    val wifiDirect: WifiDirectRadioModule
    val datastore: ScatterbrainDatastore
    val scheduler: ScatterbrainScheduler
    val prefs: RouterPreferences

    companion object {
        const val DEFAULT_TRANSACTIONTIMEOUT: Long = 120
    }
}