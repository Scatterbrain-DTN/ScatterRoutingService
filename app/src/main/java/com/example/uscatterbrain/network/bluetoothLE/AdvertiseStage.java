package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;

import com.example.uscatterbrain.ScatterProto;
import com.example.uscatterbrain.network.AdvertisePacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class AdvertiseStage {
    private final BluetoothDevice device;
    private final HashMap<String, AdvertisePacket> advertisePackets = new HashMap<>();
    private static final ArrayList<ScatterProto.Advertise.Provides> provides =
            new ArrayList<ScatterProto.Advertise.Provides>() {
        {
            add(ScatterProto.Advertise.Provides.BLE);
            add(ScatterProto.Advertise.Provides.WIFIP2P);
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
