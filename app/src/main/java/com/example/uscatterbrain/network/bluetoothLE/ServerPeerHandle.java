package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothGatt;
import android.util.Log;

import com.example.uscatterbrain.network.AckPacket;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.UpgradePacket;
import com.example.uscatterbrain.network.Utils;
import com.polidea.rxandroidble2.RxBleServerConnection;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.BehaviorSubject;

import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.ADVERTISE_CHARACTERISTIC;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UPGRADE_CHARACTERISTIC;

public class ServerPeerHandle implements PeerHandle {
    public static final String TAG = "ServerPeerHandle";
    private final RxBleServerConnection connection;
    private final CompositeDisposable peerHandleDisposable = new CompositeDisposable();
    private final AdvertisePacket advertisePacket;
    private final Observable<byte[]> advertiseWriteObservable;
    private final Observable<byte[]> upgradeWriteObservable;
    private final Completable notifyAdvertise;
    private final BehaviorSubject<BluetoothLEModule.UpgradeRequest> upgradeSubject;
    public ServerPeerHandle(
            RxBleServerConnection connection,
            AdvertisePacket advertisePacket,
            BehaviorSubject<BluetoothLEModule.UpgradeRequest> upgradeSubject
    ) {
        this.connection = connection;
        this.advertisePacket = advertisePacket;
        this.upgradeSubject = upgradeSubject;
        notifyAdvertise = notifyAdvertise();
        advertiseWriteObservable = connection.getOnCharacteristicWriteRequest(ADVERTISE_CHARACTERISTIC)
                .flatMapSingle(conn -> conn.sendReply(BluetoothGatt.GATT_SUCCESS, 0, conn.getValue())
                        .toSingleDefault(conn.getValue()));

        upgradeWriteObservable = connection.getOnCharacteristicWriteRequest(UPGRADE_CHARACTERISTIC)
                .flatMapSingle(conn -> conn.sendReply(BluetoothGatt.GATT_SUCCESS, 0, conn.getValue())
                        .toSingleDefault(conn.getValue()));
    }

    public RxBleServerConnection getConnection() {
        return connection;
    }

    public Completable notifyAdvertise() {
        return connection.setupNotifications(
                ADVERTISE_CHARACTERISTIC,
                Observable.fromArray(Utils.splitChunks(advertisePacket.getBytes()))
        );
    }

    public Single<UpgradePacket> getUpgrade() {
        return UpgradePacket.parseFrom(upgradeWriteObservable);

    }

    public Single<Boolean> notifyUpgradeAck() {
        AckPacket packet = AckPacket.newBuilder()
                .setStatus(AckPacket.Status.OK)
                .build();
        return connection.setupNotifications(
                UPGRADE_CHARACTERISTIC,
                Observable.fromArray(Utils.splitChunks(packet.getBytes()))
                .doOnComplete(() -> Log.v(TAG, "server sent ack packet"))
        ).toSingleDefault(true)
                .onErrorReturnItem(false);
    }

    @Override
    public Single<Boolean> handshake() {
        Log.d(TAG, "called handshake");
        return notifyAdvertise
                .andThen((SingleSource<AdvertisePacket>) observer -> {
                    Log.d(TAG, "handshake onCharacteristicWrite");
                    AdvertisePacket.parseFrom(advertiseWriteObservable).subscribe(observer);
                })
                .flatMap(advertise -> {
                    Log.v(TAG, "server handshake received advertise");
                    return UpgradePacket.parseFrom(upgradeWriteObservable);
                })
                .doOnSuccess(upgradePacket ->
                        upgradeSubject.onNext(
                                BluetoothLEModule.UpgradeRequest.create(
                                        BluetoothLEModule.ConnectionRole.ROLE_SEME,
                                        upgradePacket)
                        ))
                .flatMap(upgradePacket -> notifyUpgradeAck());
    }

    public void close() {
        peerHandleDisposable.dispose();
    }
}
