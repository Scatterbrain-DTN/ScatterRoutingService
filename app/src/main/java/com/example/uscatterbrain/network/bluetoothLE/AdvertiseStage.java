package com.example.uscatterbrain.network.bluetoothLE;

import androidx.annotation.Nullable;

import com.example.uscatterbrain.network.AdvertisePacket;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class AdvertiseStage {
    private final AtomicReference<AdvertisePacket> packet = new AtomicReference<>();
    private static final ArrayList<AdvertisePacket.Provides> provides =
            new ArrayList<AdvertisePacket.Provides>() {
        {
            add(AdvertisePacket.Provides.BLE);
            add(AdvertisePacket.Provides.WIFIP2P);
        }
    };
    private static final AdvertisePacket self = AdvertisePacket.newBuilder()
            .setProvides(provides)
            .build();

    public AdvertiseStage() {

    }


    public static AdvertisePacket getSelf() {
        return self;
    }

    public void addPacket(AdvertisePacket packet) {
        this.packet.set(packet);
    }

    @Nullable
    public AdvertisePacket getPackets() {
        return packet.get();
    }
}
