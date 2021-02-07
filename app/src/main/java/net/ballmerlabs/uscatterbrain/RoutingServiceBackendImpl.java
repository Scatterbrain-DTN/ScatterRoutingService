package net.ballmerlabs.uscatterbrain;

import android.util.Log;

import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler;
import com.polidea.rxandroidble2.internal.RxBleLog;

import java.util.Collections;

import javax.inject.Inject;

import io.reactivex.plugins.RxJavaPlugins;

public class RoutingServiceBackendImpl implements RoutingServiceBackend {
    public static final String TAG = "RoutingServiceBackend";
    private final BluetoothLEModule bluetoothLeRadioModule;
    private final ScatterbrainDatastore datastore;
    private final ScatterbrainScheduler scheduler;
    private final WifiDirectRadioModule radioModuleDebug;
    private final AdvertisePacket mPacket;


    @Inject
    public RoutingServiceBackendImpl(
            ScatterbrainDatastore datastore,
            BluetoothLEModule bluetoothLeRadioModule,
            ScatterbrainScheduler scheduler,
            WifiDirectRadioModule radioModuleDebug
            ) {
        RxJavaPlugins.setErrorHandler(e -> {
            Log.e(TAG, "received an unhandled exception: " + e);
            e.printStackTrace();
        });
        RxBleLog.setLogLevel(RxBleLog.DEBUG);
        this.bluetoothLeRadioModule = bluetoothLeRadioModule;
        this.datastore = datastore;
        this.scheduler = scheduler;
        this.radioModuleDebug = radioModuleDebug;
        this.mPacket = AdvertisePacket.newBuilder()
                .setProvides(Collections.singletonList(AdvertisePacket.Provides.BLE))
                .build();
    }

    @Override
    public AdvertisePacket getPacket() {
        return mPacket;
    }

    @Override
    public BluetoothLEModule getRadioModule() {
        return bluetoothLeRadioModule;
    }

    @Override
    public WifiDirectRadioModule getWifiDirect() {
        return radioModuleDebug;
    }

    @Override
    public ScatterbrainDatastore getDatastore() {
        return datastore;
    }

    @Override
    public ScatterbrainScheduler getScheduler() {
        return scheduler;
    }
}
