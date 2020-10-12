package com.example.uscatterbrain.network;

import android.bluetooth.BluetoothDevice;

import com.example.uscatterbrain.ScatterCallback;

import java.util.List;
import java.util.UUID;

public interface ScatterPeerHandler extends ScatterRadioModule {
    void setAdvertisePacket(AdvertisePacket advertisePacket);
    Observable<UUID> getOnPeersChanged();
    AdvertisePacket getAdvertisePacket();
    void startAdvertise() throws AdvertiseFailedException;
    void stopAdvertise() throws AdvertiseFailedException;
    void startDiscover(discoveryOptions opts);
    void stopDiscover();

    enum discoveryOptions {
        OPT_DISCOVER_ONCE,
        OPT_DISCOVER_FOREVER
    }

    class AdvertiseFailedException extends Exception {
        String name;
        public AdvertiseFailedException(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }
}
