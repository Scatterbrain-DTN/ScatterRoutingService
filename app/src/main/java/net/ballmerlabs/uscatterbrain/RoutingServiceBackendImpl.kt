package net.ballmerlabs.uscatterbrain

import android.util.Log
import com.polidea.rxandroidble2.internal.RxBleLog
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import javax.inject.Inject

class RoutingServiceBackendImpl @Inject constructor(
        datastore: ScatterbrainDatastore,
        bluetoothLeRadioModule: BluetoothLEModule,
        scheduler: ScatterbrainScheduler,
        radioModuleDebug: WifiDirectRadioModule
) : RoutingServiceBackend {
    override val radioModule: BluetoothLEModule
    override val datastore: ScatterbrainDatastore
    override val scheduler: ScatterbrainScheduler
    override val wifiDirect: WifiDirectRadioModule
    override val packet: AdvertisePacket?

    companion object {
        const val TAG = "RoutingServiceBackend"
    }

    init {
        RxJavaPlugins.setErrorHandler { e: Throwable ->
            Log.e(TAG, "received an unhandled exception: $e")
            e.printStackTrace()
        }
        RxBleLog.setLogLevel(RxBleLog.DEBUG)
        radioModule = bluetoothLeRadioModule
        this.datastore = datastore
        this.scheduler = scheduler
        wifiDirect = radioModuleDebug
        packet = AdvertisePacket.Companion.newBuilder()
                .setProvides(listOf(AdvertisePacket.Provides.BLE))
                .build()
    }
}