package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.util.Log;
import android.util.Pair;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.subjects.BehaviorSubject;

public class LeDeviceSession<T> {
    public static final String TAG = "LeDeviceSession";

    private final LuidStage luidStage;
    private final AdvertiseStage advertiseStage;
    private final VotingStage votingStage;
    private final ConcurrentHashMap<String, Pair<GattClientTransaction<T>, GattServerConnectionConfig>> transactionMap
            = new ConcurrentHashMap<>();
    private final BluetoothDevice device;
    private final Scheduler scheduler;
    private final BehaviorSubject<String> stageChanges = BehaviorSubject.create();
    private final ConcurrentHashMap<String, UUID> luidMap = new ConcurrentHashMap<>();
    private String stage = TransactionResult.STAGE_START;
    public LeDeviceSession(BluetoothDevice device, Scheduler scheduler) {
        this.device = device;
        this.luidStage = new LuidStage(device);
        this.advertiseStage = new AdvertiseStage(device);
        this.votingStage = new VotingStage(device);
        this.scheduler = scheduler;
    }

    public void addStage(String name, GattServerConnectionConfig stage, GattClientTransaction<T> transaction) {
        transactionMap.put(name, new Pair<>(transaction, stage));
    }

    public Single<GattServerConnectionConfig> singleServer() {
        return Single.fromCallable(() -> transactionMap.get(stage).second)
                .doOnError(err -> Log.e(TAG, "failed to get single server for stage " + stage + ": " + err))
                .onErrorResumeNext(Single.never());
    }

    public Single<GattClientTransaction<T>> singleClient() {
        return Single.fromCallable(() -> transactionMap.get(stage).first)
                .doOnError(err -> Log.e(TAG, "failed to get single client for stage " + stage + ": "+ err))
                .onErrorResumeNext(Single.never());
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

    public AdvertiseStage getAdvertiseStage() {
        return advertiseStage;
    }

    public ConcurrentHashMap<String, UUID> getLuidMap() {
        return luidMap;
    }

    public GattServerConnectionConfig getServer() {
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
