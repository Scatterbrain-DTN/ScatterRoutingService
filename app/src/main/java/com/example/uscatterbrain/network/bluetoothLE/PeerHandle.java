package com.example.uscatterbrain.network.bluetoothLE;

import java.io.Closeable;

import io.reactivex.Observable;

public interface PeerHandle extends Closeable {
    Observable<Boolean> handshake();

    @Override
    void close();
}