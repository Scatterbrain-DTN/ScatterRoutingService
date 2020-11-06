package com.example.uscatterbrain.network.bluetoothLE;

import com.example.uscatterbrain.ScatterRoutingService;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.ScatterPeerHandler;
import com.polidea.rxandroidble2.RxBleConnection;

import java.util.List;
import java.util.UUID;

import io.reactivex.Observable;

public interface BluetoothLEModule {
    Observable<UUID> getOnPeersChanged();
    void setAdvertisePacket(AdvertisePacket packet);
    AdvertisePacket getAdvertisePacket();
    void startAdvertise() throws ScatterPeerHandler.AdvertiseFailedException;
    void stopAdvertise();
    Observable<RxBleConnection> discoverOnce();
    void startDiscover(ScatterPeerHandler.discoveryOptions options);
    void stopDiscover();
    boolean startServer();
    void stopServer();
    List<UUID> getPeers();
}
