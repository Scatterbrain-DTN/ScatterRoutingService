package com.example.uscatterbrain.network.wifidirect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import com.jakewharton.rxrelay2.BehaviorRelay;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.Observable;

public class WifiDirectBroadcastReceiver extends BroadcastReceiver {

    public enum P2pState {
        STATE_DISABLED,
        STATE_ENABLED
    }
    private static final String TAG = "WifiDirectBroadcastReceiver";
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;

    private final BehaviorRelay<WifiP2pDevice> thisDeviceChangedSubject = BehaviorRelay.create();
    private final BehaviorRelay<WifiP2pInfo> connectionSubject = BehaviorRelay.create();
    private final BehaviorRelay<WifiP2pDeviceList> deviceListSubject = BehaviorRelay.create();
    private final BehaviorRelay<P2pState> p2pStateSubject = BehaviorRelay.create();

    private final  WifiP2pManager.PeerListListener mListener = deviceListSubject::accept;
    private final WifiP2pManager.ConnectionInfoListener mConnectionInfoListener = connectionSubject::accept;

    public WifiDirectBroadcastReceiver(
            WifiP2pManager manager,
            WifiP2pManager.Channel channel,
            Context context
    ) {
        this.channel = channel;
        this.manager = manager;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            Log.v(TAG, "WIFI_P2P_STATE_CHANGED_ACTION");
            // Determine if Wifi P2P mode is enabled
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                p2pStateSubject.accept(P2pState.STATE_ENABLED);
            } else {
                p2pStateSubject.accept(P2pState.STATE_DISABLED);
            }
        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            // The peer list has changed!
            Log.v(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION");
            manager.requestPeers(channel, mListener);
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            // Connection state changed!
            Log.v(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION");
            NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

            if (networkInfo != null && networkInfo.isConnected()) {
                manager.requestConnectionInfo(channel, mConnectionInfoListener);
            } else if (networkInfo != null){
                //TODO: handle disconnections
            } else {
                Log.e(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION networkinfo was null");
            }
        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            Log.v(TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION");
            WifiP2pDevice device =  intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            if (device == null) {
                Log.e(TAG, "device was null");
            } else {
                thisDeviceChangedSubject.accept(device);
            }
        }
    }


    public Observable<P2pState> observeP2pState() {
        return p2pStateSubject.delay(0, TimeUnit.SECONDS);
    }

    public Observable<WifiP2pDevice> observeThisDevice() {
        return thisDeviceChangedSubject.delay(0, TimeUnit.SECONDS);
    }

    public Observable<WifiP2pInfo> observeConnectionInfo() {
        return connectionSubject.delay(0, TimeUnit.SECONDS);
    }

    public Observable<WifiP2pDeviceList> observePeers() {
        return deviceListSubject.delay(0, TimeUnit.SECONDS);
    }

    public BroadcastReceiver asReceiver() {
        return this;
    }
}
