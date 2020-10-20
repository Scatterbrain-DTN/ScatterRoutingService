package com.example.uscatterbrain;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BluetoothLEModuleInternal;
import com.example.uscatterbrain.scheduler.ScatterbrainScheduler;

import java.util.Collections;

import javax.inject.Inject;

public class RoutingServiceBackendImpl implements RoutingServiceBackend {
    private final BluetoothLEModuleInternal bluetoothLeRadioModule;
    private final ScatterbrainDatastore datastore;
    private final ScatterbrainScheduler scheduler;
    private final AdvertisePacket mPacket;


    @Inject
    public RoutingServiceBackendImpl(
            ScatterbrainDatastore datastore,
            BluetoothLEModuleInternal bluetoothLeRadioModule,
            ScatterbrainScheduler scheduler
            ) {
        this.bluetoothLeRadioModule = bluetoothLeRadioModule;
        this.datastore = datastore;
        this.scheduler = scheduler;
        this.mPacket = AdvertisePacket.newBuilder()
                .setProvides(Collections.singletonList(ScatterProto.Advertise.Provides.BLE))
                .build();
    }

    @Override
    public AdvertisePacket getPacket() {
        return mPacket;
    }

    @Override
    public BluetoothLEModuleInternal getRadioModule() {
        return bluetoothLeRadioModule;
    }
}
