package com.example.uscatterbrain.network.bluetoothLE;

import android.util.Log;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.ElectLeaderPacket;
import com.example.uscatterbrain.network.LuidPacket;
import com.example.uscatterbrain.network.UpgradePacket;
import com.polidea.rxandroidble2.NotificationSetupMode;
import com.polidea.rxandroidble2.RxBleConnection;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_SEMAPHOR;


public class CachedLEConnection implements Disposable {

    public static final String TAG = "CachedLEConnection";
    private final RxBleConnection connection;
    private final CompositeDisposable disposable = new CompositeDisposable();
    private final ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic> channels;

    public CachedLEConnection(RxBleConnection connection, ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic> c) {
        this.channels = c;
        this.connection = connection;
    }

    public RxBleConnection getConnection() {
        return connection;
    }

    private Single<UUID> selectChannel() {
        return connection.readCharacteristic(UUID_SEMAPHOR)
                .flatMap(bytes -> {
                    final UUID uuid = BluetoothLERadioModuleImpl.bytes2uuid(bytes);
                    if (!channels.containsKey(uuid)) {
                        return Single.error(new IllegalStateException("gatt server returned invalid uuid"));
                    }
                    return Single.just(uuid);
                });
    }

    private Observable<byte[]> cachedNotification() {
        final CompositeDisposable notificationDisposable = new CompositeDisposable();
        return selectChannel()
                .flatMapObservable(uuid -> {
                    return connection.setupIndication(uuid, NotificationSetupMode.QUICK_SETUP)
                            .retry(10)
                            .doOnSubscribe(disposable -> Log.v(TAG, "client subscribed to notifications for " + uuid))
                            .flatMap(observable -> observable)
                            .doOnComplete(() -> Log.e(TAG, "notifications completed for some reason"))
                            .doOnNext(b -> Log.v(TAG, "client received bytes " + b.length))
                            .timeout(BluetoothLEModule.TIMEOUT, TimeUnit.SECONDS)
                            .doFinally(notificationDisposable::dispose);
                });
    }

    public Single<AdvertisePacket> readAdvertise() {
        return AdvertisePacket.parseFrom(cachedNotification());
    }

    public Single<UpgradePacket> readUpgrade() {
        return UpgradePacket.parseFrom(cachedNotification());
    }

    public Single<BlockHeaderPacket> readBlockHeader() {
        return BlockHeaderPacket.parseFrom(cachedNotification());
    }

    public Single<BlockSequencePacket> readBlockSequence() {
        return BlockSequencePacket.parseFrom(cachedNotification());
    }

    public Single<ElectLeaderPacket> readElectLeader() {
        return ElectLeaderPacket.parseFrom(cachedNotification());
    }

    public Single<LuidPacket> readLuid() {
        return LuidPacket.parseFrom(cachedNotification());
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
