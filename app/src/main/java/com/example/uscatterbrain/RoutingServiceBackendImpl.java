package com.example.uscatterbrain;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BluetoothLEModuleInternal;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModuleDebug;
import com.example.uscatterbrain.scheduler.ScatterbrainScheduler;

import java.util.Collections;

import javax.inject.Inject;

public class RoutingServiceBackendImpl implements RoutingServiceBackend {
    private final BluetoothLEModuleInternal bluetoothLeRadioModule;
    private final ScatterbrainDatastore datastore;
    private final ScatterbrainScheduler scheduler;
    private final WifiDirectRadioModuleDebug radioModuleDebug;
    private final AdvertisePacket mPacket;


    @Inject
    public RoutingServiceBackendImpl(
            ScatterbrainDatastore datastore,
            BluetoothLEModuleInternal bluetoothLeRadioModule,
            ScatterbrainScheduler scheduler,
            WifiDirectRadioModuleDebug radioModuleDebug
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
    public BluetoothLEModuleInternal getRadioModule() {
        return bluetoothLeRadioModule;
    }

    @Override
    public WifiDirectRadioModuleDebug getWifiDirect() {
        return radioModuleDebug;
    }
}
