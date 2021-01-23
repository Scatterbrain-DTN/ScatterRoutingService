package com.example.uscatterbrain.network.bluetoothLE;

import java.util.List;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

public interface BluetoothLEModule {
    int TIMEOUT = 2;
    void startAdvertise();
    void stopAdvertise();
    Disposable startDiscover(discoveryOptions options);
    void stopDiscover();
    boolean startServer();
    void stopServer();
    List<UUID> getPeers();
    Completable awaitTransaction();
    Observable<Boolean> observeTransactions();
    Completable discoverWithTimeout(final int timeout);
    Observable<Boolean> discoverForever();

    enum discoveryOptions {
        OPT_DISCOVER_ONCE,
        OPT_DISCOVER_FOREVER
    }

    enum ConnectionRole {
        ROLE_UKE,
        ROLE_SEME
    }
}
