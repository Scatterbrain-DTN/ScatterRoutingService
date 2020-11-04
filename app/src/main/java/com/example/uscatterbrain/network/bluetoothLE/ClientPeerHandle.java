package com.example.uscatterbrain.network.bluetoothLE;


import android.util.Log;

import com.example.uscatterbrain.ScatterProto;
import com.example.uscatterbrain.network.AckPacket;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.UpgradePacket;
import com.polidea.rxandroidble2.RxBleConnection;

import java.util.Random;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;

import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.ADVERTISE_CHARACTERISTIC;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UPGRADE_CHARACTERISTIC;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_ADVERTISE;

public class ClientPeerHandle implements PeerHandle {
    public static final String TAG = "ClientPeerHandle";
    private final RxBleConnection connection;
    private final AdvertisePacket advertisePacket;
    private final CompositeDisposable disposable = new CompositeDisposable();
    public ClientPeerHandle(
            RxBleConnection connection,
            AdvertisePacket advertisePacket
    ) {
        this.connection = connection;
        this.advertisePacket = advertisePacket;
    }

    public Completable sendUpgrade(int sessionid) {
        UpgradePacket packet = UpgradePacket.newBuilder()
                .setProvides(ScatterProto.Advertise.Provides.WIFIP2P)
                .setSessionID(sessionid)
                .build();
        return connection.createNewLongWriteBuilder()
                .setCharacteristicUuid(UPGRADE_CHARACTERISTIC.getUuid())
                .setBytes(packet.getBytes())
                .build()
                .ignoreElements();
    }

    private UpgradePacket getUpgradePacket() {
        int seqnum = Math.abs(new Random().nextInt());
        UpgradePacket upgradePacket = UpgradePacket.newBuilder()
                .setProvides(ScatterProto.Advertise.Provides.WIFIP2P)
                .setSessionID(seqnum)
                .build();
        return upgradePacket;
    }

    @Override
    public Completable handshake() {
        return connection.setupNotification(UUID_ADVERTISE)
                .doOnNext(notificationSetup -> {
                    Log.v(TAG, "client successfully set up notifications");
                })
                .flatMapSingle(AdvertisePacket::parseFrom)
                .flatMapSingle(packet -> {
                    byte[] b = packet.getBytes();
                    if (b == null) {
                        Log.e(TAG, "getBytes returned null");
                        return Single.error(new IllegalStateException("advertise packet corrupt"));
                    }
                    Log.v(TAG, "client successfully retreived advertisepacket from notification");
                    return connection.createNewLongWriteBuilder()
                            .setBytes(advertisePacket.getBytes())
                            .setCharacteristicUuid(ADVERTISE_CHARACTERISTIC.getUuid())
                            .build()
                            .ignoreElements()
                            .toSingleDefault(connection);
                })
                .flatMapSingle(connection -> {
                    UpgradePacket upgradePacket = getUpgradePacket();
                    return connection.writeCharacteristic(
                            UPGRADE_CHARACTERISTIC.getUuid(),
                            upgradePacket.getBytes()
                    )
                            .ignoreElement()
                            .toSingleDefault(connection);
                })
                .flatMapCompletable(connection -> connection.setupNotification(UPGRADE_CHARACTERISTIC.getUuid())
                        .flatMapSingle(AckPacket::parseFrom).flatMapCompletable(ackPacket -> {
                            if (ackPacket.getStatus() == AckPacket.Status.OK) {
                                return Completable.complete();
                            } else {
                                Log.e(TAG, "received ackpacket with invalid status");
                            }
                            return Completable.error(new IllegalStateException("ack packet ERR"));
                        }));

    }

    public RxBleConnection getConnection() {
        return connection;
    }

    public void close() {
        disposable.dispose();
    }
}