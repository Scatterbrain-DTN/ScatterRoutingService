package com.example.uscatterbrain.network.bluetoothLE;

import io.reactivex.Completable;

public interface GattServerConnectionConfig {
    String TAG = "GattServerConnectionConfig";

    Completable handshake(CachedLEServerConnection connection);
}
