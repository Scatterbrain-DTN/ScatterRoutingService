package com.example.uscatterbrain.network.wifidirect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;

public class WifiDirectBroadcastReceiverImpl extends BroadcastReceiver
        implements WifiDirectUnregisteredReceiver, WifiDirectBroadcastReceiver {

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final BehaviorSubject<WifiP2pDevice> thisDeviceChangedSubject = BehaviorSubject.create();
    private final BehaviorSubject<WifiP2pInfo> connectionSubject = BehaviorSubject.create();
    private final BehaviorSubject<WifiP2pDeviceList> deviceListSubject = BehaviorSubject.create();
    private final BehaviorSubject<P2pState> p2pStateSubject = BehaviorSubject.create();

    private final  WifiP2pManager.PeerListListener mListener = deviceListSubject::onNext;
    private final WifiP2pManager.ConnectionInfoListener mConnectionInfoListener = connectionSubject::onNext;

    @Inject
    public WifiDirectBroadcastReceiverImpl(
            WifiP2pManager manager,
            WifiP2pManager.Channel channel
    ) {
        this.channel = channel;
        this.manager = manager;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            // Determine if Wifi P2P mode is enabled
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                p2pStateSubject.onNext(P2pState.STATE_ENABLED);
            } else {
                p2pStateSubject.onNext(P2pState.STATE_DISABLED);
            }
        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            // The peer list has changed!
            manager.requestPeers(channel, mListener);
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            // Connection state changed!
            NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

            if (networkInfo != null && networkInfo.isConnected()) {
                manager.requestConnectionInfo(channel, mConnectionInfoListener);
            }
        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
           WifiP2pDevice device =  intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
           if (device == null) {
               thisDeviceChangedSubject.onError(new IllegalStateException("this device is null"));
           } else {
               thisDeviceChangedSubject.onNext(device);
           }
        }
    }


    @Override
    public Observable<P2pState> observeP2pState() {
        return p2pStateSubject;
    }

    @Override
    public Observable<WifiP2pDevice> observeThisDevice() {
        return thisDeviceChangedSubject;
    }

    @Override
    public Observable<WifiP2pInfo> observeConnectionInfo() {
        return connectionSubject;
    }

    @Override
    public Observable<WifiP2pDeviceList> observePeers() {
        return deviceListSubject;
    }

    @Override
    public BroadcastReceiver asReceiver() {
        return this;
    }

    @Override
    public WifiDirectBroadcastReceiver asPublic() {
        return this;
    }
}
