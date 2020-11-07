package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;

import androidx.annotation.NonNull;

import io.reactivex.Observable;

public interface WifiDirectBroadcastReceiver {
    enum P2pState {
        STATE_DISABLED,
        STATE_ENABLED
    }

    class WifiDirectDisconnectedException extends Exception {
        private final String reason;
        public WifiDirectDisconnectedException(String reason) {
            this.reason = reason;
        }

        @NonNull
        @Override
        public String toString() {
            return "Wifi direct disconnected: " + reason;
        }

        public String getReason() {
            return reason;
        }
    }

    Observable<P2pState> observeP2pState();
    Observable<WifiP2pDevice> observeThisDevice();
    Observable<WifiP2pInfo> observeConnectionInfo();
    Observable<WifiP2pDeviceList> observePeers();
}
