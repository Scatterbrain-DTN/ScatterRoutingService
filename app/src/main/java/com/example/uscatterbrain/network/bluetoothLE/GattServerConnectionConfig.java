package com.example.uscatterbrain.network.bluetoothLE;

import io.reactivex.Single;

public interface GattServerConnectionConfig<T> {
    String TAG = "GattServerConnectionConfig";

    Single<T> handshake(CachedLEServerConnection connection);
}
