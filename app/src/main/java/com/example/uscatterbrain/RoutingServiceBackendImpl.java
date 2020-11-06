package com.example.uscatterbrain;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.example.uscatterbrain.scheduler.ScatterbrainScheduler;

import java.util.Collections;

import javax.inject.Inject;

public class RoutingServiceBackendImpl implements RoutingServiceBackend {
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
        this.bluetoothLeRadioModule = bluetoothLeRadioModule;
        this.datastore = datastore;
        this.scheduler = scheduler;
        this.radioModuleDebug = radioModuleDebug;
        this.mPacket = AdvertisePacket.newBuilder()
                .setProvides(Collections.singletonList(ScatterProto.Advertise.Provides.BLE))
                .build();
        this.bluetoothLeRadioModule.setAdvertisePacket(mPacket);
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
}
