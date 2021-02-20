package net.ballmerlabs.uscatterbrain.network.bluetoothLE;

import android.content.Context;
import android.util.Log;

import net.ballmerlabs.uscatterbrain.network.AdvertisePacket;
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket;
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket;
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket;
import net.ballmerlabs.uscatterbrain.network.ElectLeaderPacket;
import net.ballmerlabs.uscatterbrain.network.IdentityPacket;
import net.ballmerlabs.uscatterbrain.network.LuidPacket;
import net.ballmerlabs.uscatterbrain.network.UpgradePacket;
import com.polidea.rxandroidble2.NotificationSetupMode;
import com.polidea.rxandroidble2.RxBleConnection;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;


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
        return connection.readCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
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

    public Single<DeclareHashesPacket> readDeclareHashes() {
        return DeclareHashesPacket.parseFrom(cachedNotification());
    }

    public Single<ElectLeaderPacket> readElectLeader() {
        return ElectLeaderPacket.parseFrom(cachedNotification());
    }

    public Single<IdentityPacket> readIdentityPacket(Context context) {
        return IdentityPacket.parseFrom(cachedNotification(), context);
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
