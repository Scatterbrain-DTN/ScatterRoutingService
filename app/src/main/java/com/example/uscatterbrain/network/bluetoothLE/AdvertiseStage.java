package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;

import com.example.uscatterbrain.network.AdvertisePacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class AdvertiseStage {
    private final BluetoothDevice device;
    private final HashMap<String, AdvertisePacket> advertisePackets = new HashMap<>();
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

    public AdvertiseStage(BluetoothDevice device) {
        this.device = device;
    }


    public static AdvertisePacket getSelf() {
        return self;
    }

    public void addPacket(AdvertisePacket packet) {
        advertisePackets.put(device.getAddress(), packet);
    }

    public Map<String, AdvertisePacket> getPackets() {
        return advertisePackets;
    }
}
