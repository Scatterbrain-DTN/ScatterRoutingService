package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.util.Log;

import com.example.uscatterbrain.network.ScatterSerializable;
import com.jakewharton.rxrelay2.PublishRelay;
import com.polidea.rxandroidble2.RxBleServerConnection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class CachedLEServerConnection implements Disposable {
    public static final String TAG = "CachedLEServerConnection";

    private final RxBleServerConnection connection;
    private final CompositeDisposable disposable = new CompositeDisposable();
    private final Map<UUID, NotificationSession> sessions = new HashMap<>();

    public CachedLEServerConnection(RxBleServerConnection connection) {
        this.connection = connection;
        for (BluetoothGattCharacteristic characteristic : BluetoothLERadioModuleImpl.mService.getCharacteristics()) {
            NotificationSession oldSession = sessions.get(characteristic.getUuid());
            if (oldSession == null) {
                final NotificationSession session = new NotificationSession();
                Disposable d =
                        connection.getOnDescriptorWriteRequest(
                                characteristic.getUuid(), BluetoothLERadioModuleImpl.UUID_CLK_DESCRIPTOR
                        )
                                .doOnNext(req -> Log.v(TAG, "received timing characteristic write request"))
                                .toFlowable(BackpressureStrategy.BUFFER)
                                .zipWith(
                                        session.packetQueue.toFlowable(BackpressureStrategy.BUFFER),
                                        (req, packet) -> req.sendReply(BluetoothGatt.GATT_SUCCESS, 0, null)
                                                .toSingleDefault(packet)
                                )
                                .flatMapSingle(packet -> packet)
                        .flatMapCompletable(packet -> {
                    Log.v(TAG, "server received timing characteristic write");
                    return connection.setupIndication(characteristic.getUuid(), packet.writeToStream(20))
                            .doOnError(session.errorRelay)
                            .doFinally(() -> session.sizeRelay.accept(session.decrementSize()));
                })
                        .subscribe(
                                () -> Log.v(TAG, "timing characteristic write handler completed"),
                                err -> Log.e(TAG, "timing characteristic handler error: " + err)
                        );
                disposable.add(d);
                sessions.putIfAbsent(characteristic.getUuid(), session);
            }
        }
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
        Log.v(TAG, "serverNotify for packet " + packet.getType());
        NotificationSession session = sessions.get(characteristic);
        if (session == null) {
            return Completable.error(new IllegalStateException("invalid uuid"));
        }

        session.incrementSize();
        return session.sizeRelay
                    .mergeWith(
                            session.errorRelay
                            .flatMap(Observable::error)
                    )
                    .takeWhile(s -> s > 0)
                    .ignoreElements()
                    .doOnSubscribe(disposable -> {
                        Log.v(TAG, "packet queue accepted packet " + packet.getType());
                        session.packetQueue.accept(packet);
                    });

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

    private static class NotificationSession {
        private final PublishRelay<ScatterSerializable> packetQueue = PublishRelay.create();
        private final PublishRelay<Integer> sizeRelay = PublishRelay.create();
        private final PublishRelay<Throwable> errorRelay = PublishRelay.create(); //TODO: handle errors
        private final AtomicReference<Integer> size = new AtomicReference<>();

        public NotificationSession() {
            size.set(0);
        }

        public int getSize() {
            return size.get();
        }

        public int decrementSize() {
            return size.updateAndGet(size -> --size);
        }

        public int incrementSize() {
            return size.updateAndGet(size -> ++size);
        }
    }
}
