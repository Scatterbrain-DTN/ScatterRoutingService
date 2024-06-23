package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.akaita.java.rxjava2debug.extensions.RxJavaAssemblyException
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Completable
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.getComponent
import net.ballmerlabs.uscatterbrain.network.TransactionError
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject
import javax.inject.Provider

class ScanBroadcastReceiverImpl : ScanBroadcastReceiver, BroadcastReceiver() {

    @Inject
    lateinit var client: RxBleClient

    @Inject
    lateinit var state: BroadcastReceiverState


    private val LOG by scatterLog()

    private fun handle(intent: Intent) {
        val r = client.backgroundScanner.onScanResultReceived(intent)

        state.batch(r)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val component = context.getComponent()
        if (component != null) {
            component.inject(this)
            handle(intent)
        }

    }
}