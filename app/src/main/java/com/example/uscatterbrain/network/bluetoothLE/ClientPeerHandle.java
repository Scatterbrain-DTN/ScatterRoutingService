package com.example.uscatterbrain.network.bluetoothLE;


import android.util.Log;

import com.example.uscatterbrain.ScatterProto;
import com.example.uscatterbrain.network.AckPacket;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.UpgradePacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.polidea.rxandroidble2.RxBleConnection;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.BehaviorSubject;

import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.ADVERTISE_CHARACTERISTIC;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UPGRADE_CHARACTERISTIC;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_ADVERTISE;

public class ClientPeerHandle implements PeerHandle {
    public static final String TAG = "ClientPeerHandle";
    private final RxBleConnection connection;
    private final AdvertisePacket advertisePacket;
    private final CompositeDisposable disposable = new CompositeDisposable();
    private final BehaviorSubject<BluetoothLEModule.UpgradeRequest> upgradeSubject;
    public ClientPeerHandle(
            RxBleConnection connection,
            AdvertisePacket advertisePacket,
            BehaviorSubject<BluetoothLEModule.UpgradeRequest> upgradeSubject
    ) {
        this.connection = connection;
        this.advertisePacket = advertisePacket;
        this.upgradeSubject = upgradeSubject;
    }

    public Completable sendUpgrade(int sessionid) {
        UpgradePacket packet = UpgradePacket.newBuilder()
                .setProvides(ScatterProto.Advertise.Provides.WIFIP2P)
                .setMetadata(WifiDirectRadioModule.UPGRADE_METADATA)
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

        upgradeSubject.onNext(BluetoothLEModule.UpgradeRequest.create(
                BluetoothLEModule.ConnectionRole.ROLE_UKE,
                upgradePacket
        ));
        return upgradePacket;
    }

    @Override
    public Observable<Boolean> handshake() {
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
                .flatMap(connection -> connection.setupNotification(UPGRADE_CHARACTERISTIC.getUuid())
                        .flatMapSingle(AckPacket::parseFrom).flatMap(ackPacket -> {
                            if (ackPacket.getStatus() == AckPacket.Status.OK) {
                                return Observable.just(true);
                            } else {
                                Log.e(TAG, "received ackpacket with invalid status");
                            }
                            return Observable.error(new IllegalStateException("ack packet ERR"));
                        }));

    }

    public RxBleConnection getConnection() {
        return connection;
    }

    public void close() {
        disposable.dispose();
    }
}