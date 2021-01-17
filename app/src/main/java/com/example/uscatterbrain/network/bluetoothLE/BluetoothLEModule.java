package com.example.uscatterbrain.network.bluetoothLE;

import com.example.uscatterbrain.network.AdvertisePacket;

import java.util.List;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.disposables.Disposable;

public interface BluetoothLEModule {
    int TIMEOUT = 2;
    void setAdvertisePacket(AdvertisePacket packet);
    AdvertisePacket getAdvertisePacket();
    void startAdvertise();
    void stopAdvertise();
    Disposable startDiscover(discoveryOptions options);
    void stopDiscover();
    boolean startServer();
    void stopServer();
    List<UUID> getPeers();
    Completable awaitTransaction();

    enum discoveryOptions {
        OPT_DISCOVER_ONCE,
        OPT_DISCOVER_FOREVER
    }

    enum ConnectionRole {
        ROLE_UKE,
        ROLE_SEME
    }
}
