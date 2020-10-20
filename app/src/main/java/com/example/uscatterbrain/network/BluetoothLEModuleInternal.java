package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterRoutingService;
import com.polidea.rxandroidble2.RxBleConnection;

import java.util.List;
import java.util.UUID;

import io.reactivex.Observable;

public interface BluetoothLEModuleInternal {
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
    UUID register(ScatterRoutingService service);
    List<UUID> getPeers();
    UUID getModuleID();
    boolean isRegistered();

}
