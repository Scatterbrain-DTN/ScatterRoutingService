package net.ballmerlabs.uscatterbrain

import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler

interface RoutingServiceBackend {
    object Applications {
        const val APPLICATION_FILESHARING = "fileshare"
    }

    val packet: AdvertisePacket?
    val radioModule: BluetoothLEModule
    val wifiDirect: WifiDirectRadioModule
    val datastore: ScatterbrainDatastore
    val scheduler: ScatterbrainScheduler
}