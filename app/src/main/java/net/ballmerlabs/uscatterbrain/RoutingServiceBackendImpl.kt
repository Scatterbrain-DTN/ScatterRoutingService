package net.ballmerlabs.uscatterbrain

import android.util.Log
import com.polidea.rxandroidble2.internal.RxBleLog
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import javax.inject.Inject

/**
 * Parent class for the entire scatterbrain backend. This is what is
 * created by dagger2 builder in ScatterRoutingService
 */
class RoutingServiceBackendImpl @Inject constructor(
        override val datastore: ScatterbrainDatastore,
        override val radioModule: BluetoothLEModule,
        override val scheduler: ScatterbrainScheduler,
        override val wifiDirect: WifiDirectRadioModule,
        override val prefs: RouterPreferences
) : RoutingServiceBackend {
    companion object {
        const val TAG = "RoutingServiceBackend"
    }

    init {
        RxJavaPlugins.setErrorHandler { e: Throwable ->
            Log.e(TAG, "received an unhandled exception: $e")
            e.printStackTrace()
        }
        RxBleLog.setLogLevel(RxBleLog.DEBUG) //TODO: disable this in production
    }
}