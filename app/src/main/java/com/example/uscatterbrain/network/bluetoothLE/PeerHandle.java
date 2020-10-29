package com.example.uscatterbrain.network.bluetoothLE;

import java.io.Closeable;

import io.reactivex.Completable;

public interface PeerHandle extends Closeable {
    Completable handshake();

    @Override
    void close();
}