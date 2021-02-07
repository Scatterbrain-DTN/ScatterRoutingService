package net.ballmerlabs.uscatterbrain.network.bluetoothLE;


import io.reactivex.Single;

public interface GattClientTransaction<T> {
    String TAG = "GattClientTransaction";
    Single<T> handshake(CachedLEConnection connection);
}