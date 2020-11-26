package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;

import com.example.uscatterbrain.network.AdvertisePacket;

import java.util.HashMap;
import java.util.Map;

public class AdvertiseStage {
    private final BluetoothDevice device;
    private final HashMap<String, AdvertisePacket> advertisePackets = new HashMap<>();

    public AdvertiseStage(BluetoothDevice device) {
        this.device = device;
    }

    public void addPacket(AdvertisePacket packet) {
        advertisePackets.put(device.getAddress(), packet);
    }

    public Map<String, AdvertisePacket> getPackets() {
        return advertisePackets;
    }
}
