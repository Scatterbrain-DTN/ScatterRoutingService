package com.example.uscatterbrain.scheduler;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.disposables.CompositeDisposable;

@Singleton
public class ScatterbrainSchedulerImpl implements ScatterbrainScheduler {
    static final String TAG = "Scheduler";
    private final AtomicReference<RoutingServiceState>  mState;
    private final BluetoothLEModule bluetoothLEModule;
    private final WifiDirectRadioModule wifiDirectRadioModule;
    private final ScatterbrainDatastore datastore;
    private final CompositeDisposable globalDisposable = new CompositeDisposable();

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
    }

    @Override
    public boolean stop() {
        bluetoothLEModule.stopAdvertise();
        bluetoothLEModule.stopServer();
        globalDisposable.dispose();
        return true;
    }
}
