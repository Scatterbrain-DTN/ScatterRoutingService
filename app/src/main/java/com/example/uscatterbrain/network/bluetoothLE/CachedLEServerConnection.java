package com.example.uscatterbrain.network.bluetoothLE;

import android.util.Log;

import com.example.uscatterbrain.network.ScatterSerializable;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.polidea.rxandroidble2.RxBleServerConnection;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.CompletableSubject;

public class CachedLEServerConnection implements Disposable {
    public static final String TAG = "CachedLEServerConnection";

    private final RxBleServerConnection connection;
    private final CompositeDisposable disposable = new CompositeDisposable();
    private final ConcurrentHashMap<UUID, BehaviorRelay<byte[]>> notififcationMap = new ConcurrentHashMap<>();

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
        final BehaviorRelay<byte[]> subject = notififcationMap.get(characteristic);

        if (subject == null) {
            BehaviorRelay<byte[]> bh  = BehaviorRelay.create();
            notififcationMap.put(characteristic, bh);
            return connection.setupNotifications(
                    characteristic
            )
                    .doOnError(err -> Log.e(TAG, "error in server notify: " + err))
                    .doOnDispose(() -> Log.v(TAG, "gatt server for packet " + packet.getType() + " disposed"))
                    .flatMapCompletable(
                            behaviorProcessor -> {
                                final CompletableSubject s = CompletableSubject.create();
                                bh.toFlowable(BackpressureStrategy.BUFFER).subscribe(behaviorProcessor);
                                Disposable d = packet.writeToStream(20)
                                        .doOnComplete(s::onComplete)
                                        .doOnError(s::onError)
                                        .subscribe(bh);
                                disposable.add(d);
                                return s;
                                });
        } else {
            CompletableSubject completableSubject = CompletableSubject.create();
            Disposable d = packet.writeToStream(20)
                    .doOnComplete(completableSubject::onComplete)
                    .doOnError(completableSubject::onError)
                    .subscribe(subject);

            disposable.add(d);
            return completableSubject;
        }
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
