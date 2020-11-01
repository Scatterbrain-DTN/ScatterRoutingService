package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.ScatterPeerHandler;
import com.example.uscatterbrain.network.ScatterRadioModule;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiDirectRadioModuleImpl implements ScatterPeerHandler, ScatterRadioModule, WifiDirectRadioModuleDebug {
    private static final String TAG = "WifiDirectRadioModule";
    private final WifiP2pManager mManager;
    private final WifiDirectBroadcastReceiver mBroadcastReceiver;
    private final WifiP2pManager.Channel mP2pChannel;
    private static final String GROUP_NAME = "DIRECT-scattertest";
    private static final String GROUP_PASSPHRASE = "youwillneverguessthis";
    private static final WifiP2pConfig config = new WifiP2pConfig.Builder()
            .setNetworkName(GROUP_NAME)
            .setPassphrase(GROUP_PASSPHRASE)
            .build();

    @Inject
    public WifiDirectRadioModuleImpl(
            WifiP2pManager manager,
            WifiDirectBroadcastReceiver receiver,
            WifiP2pManager.Channel channel
    ) {
        this.mManager = manager;
        this.mBroadcastReceiver = receiver;
        this.mP2pChannel = channel;
        Disposable d = mBroadcastReceiver.observeConnectionState()
                .subscribe(
                        success -> Log.v(TAG, "connection state change: " + success.toString()),
                        err -> Log.v(TAG, "error on state change: " + err)
                );

        Disposable d2 = mBroadcastReceiver.observeP2pState()
                .subscribe(
                        success -> Log.v(TAG, "p2p state change: " + success.toString()),
                        err -> Log.e(TAG, "error on p2p state change: " + err)
                );

        Disposable d3 = mBroadcastReceiver.observePeers()
                .subscribe(
                        success -> Log.v(TAG, "peers changed: " + success.toString()),
                        err -> Log.e(TAG, "error when fetching peer list: " + err)
                );

        Disposable d4 = mBroadcastReceiver.observeThisDevice()
                .subscribe(
                        success -> Log.v(TAG, "this device changed: " + success.toString()),
                        err -> Log.e(TAG, "error during this device change: " + err)
                );
    }

    @Override
    public void setAdvertisePacket(AdvertisePacket advertisePacket) {

    }

    @Override
    public Observable<UUID> getOnPeersChanged() {
        return null;
    }

    @Override
    public AdvertisePacket getAdvertisePacket() {
        return null;
    }

    @Override
    public void startAdvertise() throws AdvertiseFailedException {

    }

    @Override
    public void stopAdvertise() throws AdvertiseFailedException {

    }

    @Override
    public void createGroup() {
        mManager.createGroup(mP2pChannel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.v(TAG, "created wifi direct group");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "failed to create wifi direct group");
            }
        });
    }

    @Override
    public void connectToGroup() {
        mManager.connect(mP2pChannel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.v(TAG, "connected to wifi direct group! FMEEEEE! AM HAPPY!");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "failed to connect to wifi direct group, am v sad. I cry now.");
            }
        });
    }

    @Override
    public void startDiscover(discoveryOptions opts) {

    }

    @Override
    public void stopDiscover() {

    }

    @Override
    public List<UUID> getPeers() {
        return null;
    }
}
