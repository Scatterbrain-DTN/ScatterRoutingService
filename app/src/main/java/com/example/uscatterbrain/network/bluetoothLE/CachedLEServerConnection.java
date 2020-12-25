package com.example.uscatterbrain.network.bluetoothLE;

import android.util.Log;

import com.example.uscatterbrain.network.ScatterSerializable;
import com.polidea.rxandroidble2.RxBleServerConnection;

import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class CachedLEServerConnection implements Disposable {
    public static final String TAG = "CachedLEServerConnection";

    private final RxBleServerConnection connection;
    private final CompositeDisposable disposable = new CompositeDisposable();

    public CachedLEServerConnection(RxBleServerConnection connection) {
        this.connection = connection;
    }

    public Disposable setDefaultReply(UUID characteristic, int reply) {
        Disposable d =  connection.getOnCharacteristicWriteRequest(characteristic)
                .subscribe(
                        trans ->  trans.sendReply(reply, 0, trans.getValue()).subscribe(),
                        err -> Log.v(TAG, "failed to set default reply: " + err)
                );

        disposable.add(d);
        return d;
    }

    public   <T extends ScatterSerializable> Completable serverNotify(
            T packet,
            UUID characteristic
    ) {

            return connection.setupNotifications(
                    characteristic,
                    packet.writeToStream(20)
            )
                    .doOnError(err -> Log.e(TAG, "error in server notify: " + err))
                    .doOnDispose(() -> Log.v(TAG, "gatt server for packet " + packet.getType() + " disposed"));
    }

    public RxBleServerConnection getConnection() {
        return connection;
    }

    @Override
    public void dispose() {
        disposable.dispose();
    }

    @Override
    public boolean isDisposed() {
        return disposable.isDisposed();
    }
}
