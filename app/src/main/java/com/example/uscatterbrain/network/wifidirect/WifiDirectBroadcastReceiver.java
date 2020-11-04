package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;

import io.reactivex.Observable;

public interface WifiDirectBroadcastReceiver {
    enum P2pState {
        STATE_DISABLED,
        STATE_ENABLED
    }

    Observable<P2pState> observeP2pState();
    Observable<WifiP2pDevice> observeThisDevice();
    Observable<WifiP2pInfo> observeConnectionInfo();
    Observable<WifiP2pDeviceList> observePeers();
}
