package com.example.uscatterbrain.network.bluetoothLE;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.UpgradePacket;

import java.util.List;
import java.util.UUID;

import io.reactivex.Observable;

public interface BluetoothLEModule {
    void setAdvertisePacket(AdvertisePacket packet);
    AdvertisePacket getAdvertisePacket();
    void startAdvertise();
    void stopAdvertise();
    void startDiscover(discoveryOptions options);
    void stopDiscover();
    boolean startServer();
    void stopServer();
    List<UUID> getPeers();

    enum discoveryOptions {
        OPT_DISCOVER_ONCE,
        OPT_DISCOVER_FOREVER
    }

    enum ConnectionRole {
        ROLE_UKE,
        ROLE_SEME
    }
}
