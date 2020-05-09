package com.example.uscatterbrain.network;

import java.util.List;

public interface ScatterPeerHandler extends ScatterRadioModule {
    void setAdvertisePacket(AdvertisePacket advertisePacket);
    void setOnPeersChanged(PeersChangedCallback callback);
    AdvertisePacket getAdvertisePacket();
    void startAdvertise() throws AdvertiseFailedException;
    void stopAdvertise() throws AdvertiseFailedException;
    void startDiscover() throws AdvertiseFailedException;
    void stopDiscover() throws AdvertiseFailedException;

    interface PeersChangedCallback {
        void onPeersChanged(List<AdvertisePacket> peers);
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
