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
import io.reactivex.disposables.Disposable;

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
    public ServerPeerHandle(
            RxBleServerConnection connection,
            AdvertisePacket advertisePacket
    ) {
        this.connection = connection;
        this.advertisePacket = advertisePacket;
        notifyAdvertise = notifyAdvertise();
        advertiseWriteObservable = connection.getOnCharacteristicWriteRequest(ADVERTISE_CHARACTERISTIC)
                .flatMapSingle(conn -> conn.sendReply(BluetoothGatt.GATT_SUCCESS, 0, conn.getValue())
                        .toSingleDefault(conn.getValue()));

        upgradeWriteObservable = connection.getOnCharacteristicWriteRequest(UPGRADE_CHARACTERISTIC)
                .flatMapSingle(conn -> conn.sendReply(BluetoothGatt.GATT_SUCCESS, 0, conn.getValue())
                        .toSingleDefault(conn.getValue()));

        Disposable d1 = upgradeWriteObservable.subscribe(
                write -> Log.v(TAG, "server upgrade packet characteristic write len " + write.length),
                err -> Log.v(TAG, "server upgrade packet characteristic write failed: " + err)
        );

        Disposable d2 = advertiseWriteObservable.subscribe(
                write ->  Log.v(TAG, "server characteristic write len " + write.length),
                err -> Log.e(TAG, "error in characteristicWrite: " + err)
        );
        peerHandleDisposable.add(d1);
        peerHandleDisposable.add(d2);
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

    public Completable notifyUpgradeAck() {
        AckPacket packet = AckPacket.newBuilder()
                .setStatus(AckPacket.Status.OK)
                .build();
        return connection.setupNotifications(
                UPGRADE_CHARACTERISTIC,
                Observable.fromArray(Utils.splitChunks(packet.getBytes()))
                .doOnComplete(() -> Log.v(TAG, "server sent ack packet"))
        );
    }

    @Override
    public Completable handshake() {
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
                .flatMapCompletable(upgradePacket -> notifyUpgradeAck());
    }

    public void close() {
        peerHandleDisposable.dispose();
    }
}
