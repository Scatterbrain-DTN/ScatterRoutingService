package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.network.LuidPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class LuidStage {
    private final HashMap<String, LuidPacket> hashPackets = new HashMap<>();
    private final HashMap<String, LuidPacket> realPackets = new HashMap<>();
    private final AtomicReference<LuidPacket> selfhashed = new AtomicReference<>();
    private UUID uuid;
    private final AtomicReference<LuidPacket> self = new AtomicReference<>();
    private final BluetoothDevice device;

    public LuidStage(BluetoothDevice device) {
        this.device = device;
        regenerateUUID();
    }

    public void regenerateUUID() {
        uuid = UUID.randomUUID();
    }

    private Single<LuidPacket> createSelf(boolean hashed) {
        return Single.fromCallable(() -> {
            LuidPacket.Builder builder = LuidPacket.newBuilder()
                    .setLuid(uuid);

            if (hashed) {
                builder.enableHashing();
            }
            return builder.build();
        })
                .doOnSuccess(packet -> {
                    Log.v("debug", "created luid packet: " + packet.getLuid());
                        if (hashed) {
                            selfhashed.set(packet);
                        } else {
                            self.set(packet);
                        }
                });
    }

    public Single<LuidPacket> getSelfHashed() {
        LuidPacket packet = selfhashed.get();
        if (packet == null) {
            return createSelf(true);
        } else {
            return Single.just(packet);
        }
    }

    public Single<LuidPacket> getSelf() {
        LuidPacket packet = self.get();
        if (packet == null) {
            return createSelf(false);
        } else {
            return Single.just(packet);
        }
    }


    public UUID getLuid() {
        LuidPacket s = self.get();
        if (s == null) {
            return null;
        }

        return s.getLuid();
    }

    public void addPacket(LuidPacket packet) {
        if (packet.isHashed()) {
            hashPackets.put(device.getAddress(), packet);
        } else {
            realPackets.put(device.getAddress(), packet);
        }
    }

    public Completable verifyPackets() {
            if (hashPackets.size() != realPackets.size()) {
                return Completable.error(new InvalidLuidException("size conflict " +
                        hashPackets.size() + " " + realPackets.size()));
            }

            return Observable.zip(
                    Observable.fromIterable(hashPackets.values()),
                    Observable.fromIterable(realPackets.values()),
                    LuidPacket::verifyHash
            )
                    .flatMap(bool -> {
                        if (! bool) {
                            return Observable.error(new InvalidLuidException("failed to verify hash"));
                        } else {
                            return Observable.just(true);
                        }
                    })
                    .ignoreElements();
    }

    public Map<String, LuidPacket> getPackets() {
        return realPackets;
    }

    public Map<String, LuidPacket> getHashedPackets() {
        return hashPackets;
    }

    public static class InvalidLuidException extends Exception {
        private final String reason;

        public InvalidLuidException(String reason) {
            this.reason = reason;
        }

        @NonNull
        @Override
        public String toString() {
            return "invalid state in luid stage: " + reason;
        }
    }
}
