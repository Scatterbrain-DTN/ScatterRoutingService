package com.example.uscatterbrain.network.bluetoothLE;

import java.io.Closeable;

import io.reactivex.Observable;
import io.reactivex.Single;

public interface PeerHandle extends Closeable {
    Single<Boolean> handshake();

    @Override
    void close();
}