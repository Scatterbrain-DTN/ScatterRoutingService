package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothGattCharacteristic;
import android.util.Log;

import com.example.uscatterbrain.network.ScatterSerializable;
import com.github.davidmoten.rx2.Bytes;
import com.polidea.rxandroidble2.RxBleServerConnection;

import java.io.ByteArrayInputStream;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class ServerPeerHandle {
    public static final String TAG = "ServerPeerHandle";
    private final RxBleServerConnection connection;
    private final CompositeDisposable peerHandleDisposable = new CompositeDisposable();
    public ServerPeerHandle(
            RxBleServerConnection connection
    ) {
        this.connection = connection;
    }


    public void setDefaultReply(BluetoothGattCharacteristic characteristic, int reply) {
        connection.getOnCharacteristicWriteRequest(characteristic)
                .flatMap(conn -> conn.sendReply(reply, 0, conn.getValue()).toObservable())
                .onErrorResumeNext(Observable.empty())
                .subscribe();
    }

    public RxBleServerConnection getConnection() {
        return connection;
    }

    public <T extends ScatterSerializable> void serverNotify(
            T packet,
            BluetoothGattCharacteristic characteristic
    ) {
        Disposable d = notifyPacket(packet, characteristic)
                .subscribe(
                        () -> Log.v(TAG, "server successfully sent notification on " + characteristic.getUuid()),
                        err -> Log.e(TAG, "server failed to send notification: " + err)
                );
        peerHandleDisposable.add(d);
    }

    private  <T extends ScatterSerializable> Completable notifyPacket(
            T packet,
            BluetoothGattCharacteristic characteristic
    ) {
        Log.v(TAG, "called notifyPacket: " + characteristic.getUuid());
        return connection.setupNotifications(
                characteristic,
                Bytes.from(new ByteArrayInputStream(packet.getBytes()), 10).toObservable()
        )
                .doOnError(err -> Log.e(TAG, "error in notify: " + err))
                .onErrorComplete();
    }

    public Single<Boolean> handshake() {
        Log.d(TAG, "called handshake");
        return Single.just(true);
    }

    public void close() {
        peerHandleDisposable.dispose();
    }
}
