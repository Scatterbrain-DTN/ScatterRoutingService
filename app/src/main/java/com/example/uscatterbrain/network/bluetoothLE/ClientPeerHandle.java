package com.example.uscatterbrain.network.bluetoothLE;


import android.util.Log;

import com.example.uscatterbrain.network.AckPacket;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.ElectLeaderPacket;
import com.example.uscatterbrain.network.LuidPacket;
import com.example.uscatterbrain.network.UpgradePacket;
import com.polidea.rxandroidble2.RxBleConnection;

import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;

import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_ADVERTISE;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_UPGRADE;

public abstract class ClientPeerHandle {
    public static final String TAG = "ClientPeerHandle";
    private final CompositeDisposable disposable = new CompositeDisposable();
    public ClientPeerHandle() {
    }


    public Single<AdvertisePacket> readAdvertise(RxBleConnection connection) {
        return connection.
                setupNotification(UUID_ADVERTISE)
                .doOnNext(notificationSetup -> Log.v(TAG, "client successfully set up notifications for advertise"))
                .flatMapSingle(AdvertisePacket::parseFrom)
                .firstOrError();
    };

    public Single<UpgradePacket> readUpgrade(RxBleConnection connection) {
        return connection.
                setupNotification(UUID_UPGRADE)
                .doOnNext(notificationSetup -> Log.v(TAG, "client successfully setup notifications for upgrade"))
                .flatMapSingle(UpgradePacket::parseFrom)
                .firstOrError();
    }

    public Single<AckPacket> readAck(RxBleConnection connection) {
        return connection.
                setupNotification(UUID_UPGRADE)
                .doOnNext(notificationSetup -> Log.v(TAG, "client successfully setup notifications for ack"))
                .flatMapSingle(AckPacket::parseFrom)
                .firstOrError();
    }

    public Single<BlockHeaderPacket> readBlockHeader(RxBleConnection connection) {
        return connection.
                setupNotification(UUID_UPGRADE)
                .doOnNext(notificationSetup -> Log.v(TAG, "client successfully setup notifications for blockheader"))
                .flatMapSingle(BlockHeaderPacket::parseFrom)
                .firstOrError();
    }

    public Single<BlockSequencePacket> readBlockSequence(RxBleConnection connection) {
        return connection.
                setupNotification(UUID_UPGRADE)
                .doOnNext(notificationSetup -> Log.v(TAG, "client successfully setup notifications for blocksequence"))
                .flatMapSingle(BlockSequencePacket::parseFrom)
                .firstOrError();
    }

    public Single<ElectLeaderPacket> readElectLeader(RxBleConnection connection) {
        return connection.
                setupNotification(UUID_UPGRADE)
                .doOnNext(notificationSetup -> Log.v(TAG, "client successfully setup notifications for electleader"))
                .flatMapSingle(ElectLeaderPacket::parseFrom)
                .firstOrError();
    }

    public Single<LuidPacket> readLuid(RxBleConnection connection) {
        return connection.
                setupNotification(UUID_UPGRADE)
                .doOnNext(notificationSetup -> Log.v(TAG, "client successfully setup notifications for luid"))
                .flatMapSingle(LuidPacket::parseFrom)
                .firstOrError();
    }

    private Single<BluetoothLEModule.UpgradeRequest> doElection() {
        return null;
    }

    public abstract Single<Boolean> handshake(RxBleConnection connection);

    public void close() {
        disposable.dispose();
    }
}