package net.ballmerlabs.uscatterbrain.scheduler;

import android.util.Log;

import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.disposables.Disposable;

@Singleton
public class ScatterbrainSchedulerImpl implements ScatterbrainScheduler {
    static final String TAG = "Scheduler";
    private final AtomicReference<RoutingServiceState>  mState;
    private final BluetoothLEModule bluetoothLEModule;
    private final WifiDirectRadioModule wifiDirectRadioModule;
    private final ScatterbrainDatastore datastore;
    private final AtomicReference<Disposable> globalDisposable = new AtomicReference<>();

    @Inject
    public ScatterbrainSchedulerImpl(
            WifiDirectRadioModule wifiDirectRadioModule,
            BluetoothLEModule bluetoothLEModule,
            ScatterbrainDatastore scatterbrainDatastore
    ) {
        this.mState = new AtomicReference<>(RoutingServiceState.STATE_SUSPEND);
        this.wifiDirectRadioModule = wifiDirectRadioModule;
        this.bluetoothLEModule = bluetoothLEModule;
        this.datastore = scatterbrainDatastore;
    }

    @Override
    public RoutingServiceState getRoutingServiceState() {
        return this.mState.get();
    }

    @Override
    public void start() {
        bluetoothLEModule.startAdvertise();
        bluetoothLEModule.startServer();
        final Disposable d = bluetoothLEModule.discoverForever()
                .subscribe(
                        res -> Log.v(TAG, "finished transaction: " + res),
                        err -> Log.e(TAG, "error in transaction: " + err)
                );

        globalDisposable.getAndUpdate(disp -> {
            if (disp != null) {
                disp.dispose();
            }
            return d;
        });

    }

    @Override
    public boolean stop() {
        bluetoothLEModule.stopAdvertise();
        bluetoothLEModule.stopServer();
        globalDisposable.getAndUpdate(disp -> {
            if (disp != null) {
                disp.dispose();
            }
            return null;
        });
        return true;
    }
}
