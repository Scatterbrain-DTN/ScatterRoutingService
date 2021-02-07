package net.ballmerlabs.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothGatt;
import android.util.Log;

import net.ballmerlabs.uscatterbrain.network.ScatterSerializable;
import com.jakewharton.rxrelay2.PublishRelay;
import com.polidea.rxandroidble2.RxBleServerConnection;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class CachedLEServerConnection implements Disposable {
    public static final String TAG = "CachedLEServerConnection";

    private final RxBleServerConnection connection;
    private final CompositeDisposable disposable = new CompositeDisposable();
    private final PublishRelay<ScatterSerializable> packetQueue = PublishRelay.create();
    private final PublishRelay<Integer> sizeRelay = PublishRelay.create();
    private final PublishRelay<Throwable> errorRelay = PublishRelay.create(); //TODO: handle errors
    private final AtomicReference<Integer> size = new AtomicReference<>(0);
    private final ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic> channels;

    public CachedLEServerConnection(RxBleServerConnection connection, ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic> notif) {
        this.connection = connection;
        this.channels = notif;
        Disposable d =
                connection.getOnCharacteristicReadRequest(
                        BluetoothLERadioModuleImpl.UUID_SEMAPHOR
                )
                        .doOnNext(req -> Log.v(TAG, "received timing characteristic write request"))
                        .toFlowable(BackpressureStrategy.BUFFER)
                        .flatMapSingle(req -> {
                            return selectCharacteristic()
                                    .flatMap(characteristic ->
                                            req.sendReply(BluetoothGatt.GATT_SUCCESS, 0,
                                                    BluetoothLERadioModuleImpl.uuid2bytes(characteristic.getUuid()))
                                            .toSingleDefault(characteristic)
                                    )
                                    .timeout(2, TimeUnit.SECONDS)
                                    .doOnError(err -> req.sendReply(BluetoothGatt.GATT_FAILURE, 0, null));
                        })
                        .zipWith(packetQueue.toFlowable(BackpressureStrategy.BUFFER), (ch, packet) -> {
                            Log.v(TAG, "server received timing characteristic write");
                            return connection.setupIndication(ch.getUuid(), packet.writeToStream(20))
                                    .doOnError(errorRelay)
                                    .doFinally(() -> {
                                        sizeRelay.accept(decrementSize());
                                        ch.release();
                                    });
                        })
                        .flatMapCompletable(completable -> completable)
                        .repeat()
                        .retry()
                        .subscribe(
                                () -> Log.v(TAG, "timing characteristic write handler completed"),
                                err -> Log.e(TAG, "timing characteristic handler error: " + err)
                        );
        disposable.add(d);
    }

    public Single<BluetoothLERadioModuleImpl.OwnedCharacteristic> selectCharacteristic() {
        return Observable.mergeDelayError(
                Observable.fromIterable(channels.values())
                        .map(lockedCharactersitic -> lockedCharactersitic.awaitCharacteristic().toObservable())
        )
                .firstOrError()
                .doOnSuccess(ch -> Log.v(TAG, "selected characteristic: " + ch.getCharacteristic()));
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

    private synchronized <T extends ScatterSerializable> Completable enqueuePacket(T packet) {
        incrementSize();
       return sizeRelay
                .mergeWith(
                        errorRelay
                                .flatMap(Observable::error)
                )
                .takeWhile(s -> s > 0)
                .ignoreElements()
                .doOnSubscribe(disposable -> {
                    Log.v(TAG, "packet queue accepted packet " + packet.getType());
                    packetQueue.accept(packet);
                });
    }


    private int getSize() {
        return size.get();
    }

    private int decrementSize() {
        return size.updateAndGet(size -> --size);
    }

    private int incrementSize() {
        return size.updateAndGet(size -> ++size);
    }

    public synchronized <T extends ScatterSerializable> Completable serverNotify(
            T packet
    ) {
        Log.v(TAG, "serverNotify for packet " + packet.getType());
        if (size.get() <= 0) {
            return enqueuePacket(packet);
        } else {
            return sizeRelay
                    .takeWhile(s -> s > 0)
                    .ignoreElements()
                    .andThen(enqueuePacket(packet))
                    .timeout(BluetoothLEModule.TIMEOUT, TimeUnit.SECONDS);
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
