package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.util.Log;
import android.util.Pair;

import com.example.uscatterbrain.network.AdvertisePacket;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.subjects.BehaviorSubject;

public class LeDeviceSession<T,U> {
    public static final String TAG = "LeDeviceSession";

    private final LuidStage luidStage;
    private final AdvertiseStage advertiseStage;
    private final VotingStage votingStage;
    private UpgradeStage upgradeStage;
    private final ConcurrentHashMap<String, Pair<GattClientTransaction<T>, GattServerConnectionConfig<U>>> transactionMap
            = new ConcurrentHashMap<>();
    private final BluetoothDevice device;
    private final Scheduler scheduler;
    private final BehaviorSubject<String> stageChanges = BehaviorSubject.create();
    private final ConcurrentHashMap<String, UUID> luidMap = new ConcurrentHashMap<>();
    private String stage = TransactionResult.STAGE_START;
    private BluetoothLEModule.ConnectionRole connectionRole = BluetoothLEModule.ConnectionRole.ROLE_UKE;
    public LeDeviceSession(BluetoothDevice device, Scheduler scheduler) {
        this.device = device;
        this.luidStage = new LuidStage(device);
        this.advertiseStage = new AdvertiseStage(device);
        this.votingStage = new VotingStage(device);
        this.scheduler = scheduler;
    }

    public void addStage(String name, GattServerConnectionConfig<U> stage, GattClientTransaction<T> transaction) {
        transactionMap.put(name, new Pair<>(transaction, stage));
    }

    public Single<GattServerConnectionConfig<U>> singleServer() {
        return Single.fromCallable(() -> transactionMap.get(stage).second)
                .doOnError(err -> Log.e(TAG, "failed to get single server for stage " + stage + ": " + err))
                .onErrorResumeNext(Single.never());
    }

    public Single<GattClientTransaction<T>> singleClient() {
        return Single.fromCallable(() -> transactionMap.get(stage).first)
                .doOnError(err -> Log.e(TAG, "failed to get single client for stage " + stage + ": "+ err))
                .onErrorResumeNext(Single.never());
    }

    public BluetoothLEModule.ConnectionRole getRole() {
        return connectionRole;
    }

    public void setRole(BluetoothLEModule.ConnectionRole role) {
        this.connectionRole = role;
    }

    public Observable<String> observeStage() {
        return stageChanges
                .takeWhile(s -> s.compareTo(TransactionResult.STAGE_EXIT) != 0)
                .delay(0, TimeUnit.SECONDS, scheduler);
    }

    public VotingStage getVotingStage() {
        return votingStage;
    }

    public LuidStage getLuidStage() {
        return luidStage;
    }

    public void setUpgradeStage(AdvertisePacket.Provides provides) {
        upgradeStage = new UpgradeStage(provides);
    }

    public UpgradeStage getUpgradeStage() {
        if (upgradeStage == null) {
            throw new IllegalStateException("upgrade stage not set");
        }

        return upgradeStage;
    }

    public AdvertiseStage getAdvertiseStage() {
        return advertiseStage;
    }

    public ConcurrentHashMap<String, UUID> getLuidMap() {
        return luidMap;
    }

    public GattServerConnectionConfig<U> getServer() {
        return transactionMap.get(stage).second;
    }

    public GattClientTransaction<T> getClient() {
        return transactionMap.get(stage).first;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
        stageChanges.onNext(stage);
    }

    public BluetoothDevice getDevice() {
        return device;
    }
}
