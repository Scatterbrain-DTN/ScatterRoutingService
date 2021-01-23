package com.example.uscatterbrain.network.bluetoothLE;

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
