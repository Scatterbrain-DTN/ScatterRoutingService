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
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.SERVICE_UUID;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_ADVERTISE;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_BLOCKDATA;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_BLOCKSEQUENCE;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_CLK_DESCRIPTOR;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_ELECTIONLEADER;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_LUID;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_UPGRADE;

public class CachedLEConnection implements Disposable {

    public static final String TAG = "CachedLEConnection";
    private final RxBleConnection connection;
    private final CompositeDisposable disposable = new CompositeDisposable();

    public CachedLEConnection(RxBleConnection connection) {
        this.connection = connection;
    }

    public RxBleConnection getConnection() {
        return connection;
    }

    private Observable<byte[]> cachedNotification(UUID uuid) {
        final CompositeDisposable notificationDisposable = new CompositeDisposable();
        return connection.setupIndication(uuid, NotificationSetupMode.QUICK_SETUP)
                .doOnSubscribe(disposable -> Log.v(TAG, "client subscribed to notifications for " + uuid))
                .flatMap(observable -> observable)
                .doOnComplete(() -> Log.e(TAG, "notifications completed for some reason"))
                .doOnNext(b -> Log.v(TAG, "client received bytes " + b.length))
                .doOnSubscribe(disp -> {
                    Disposable d = connection.writeDescriptor(SERVICE_UUID, uuid, UUID_CLK_DESCRIPTOR, new byte[0])
                            .subscribe(
                                    () -> Log.v(TAG, "successfually wrote timing characteristic"),
                                    err -> Log.e(TAG, "failed to write timing characteristic: " + err)
                            );
                            notificationDisposable.add(d);
                })
                .timeout(BluetoothLEModule.TIMEOUT, TimeUnit.SECONDS)
                .doFinally(notificationDisposable::dispose);
    }

    public Single<AdvertisePacket> readAdvertise() {
        return AdvertisePacket.parseFrom(cachedNotification(UUID_ADVERTISE));
    }

    public Single<UpgradePacket> readUpgrade() {
        return UpgradePacket.parseFrom(cachedNotification(UUID_UPGRADE));
    }

    public Single<BlockHeaderPacket> readBlockHeader() {
        return BlockHeaderPacket.parseFrom(cachedNotification(UUID_BLOCKDATA));
    }

    public Single<BlockSequencePacket> readBlockSequence() {
        return BlockSequencePacket.parseFrom(cachedNotification(UUID_BLOCKSEQUENCE));
    }

    public Single<ElectLeaderPacket> readElectLeader() {
        return ElectLeaderPacket.parseFrom(cachedNotification(UUID_ELECTIONLEADER));
    }

    public Single<LuidPacket> readLuid() {
        return LuidPacket.parseFrom(cachedNotification(UUID_LUID));
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
