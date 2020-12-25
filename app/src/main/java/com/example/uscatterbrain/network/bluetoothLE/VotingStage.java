package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import com.example.uscatterbrain.ScatterProto;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.ElectLeaderPacket;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class VotingStage {
    private final BluetoothDevice device;
    private final HashMap<String, ElectLeaderPacket> hashedPackets = new HashMap<>();
    private final HashMap<String, ElectLeaderPacket> unhashedPackets = new HashMap<>();
    private final UUID tiebreaker = UUID.randomUUID();

    public VotingStage(BluetoothDevice device) {
        this.device = device;
    }

    public ElectLeaderPacket getSelf(boolean hashed) {
        ElectLeaderPacket.Builder builder = ElectLeaderPacket.newBuilder();

        if (hashed) {
            builder.enableHashing();
        }

        //TODO: add ability to change this
        return builder
                .setProvides(AdvertisePacket.Provides.WIFIP2P)
                .setTiebreaker(tiebreaker)
                .build();
    }

    public void addPacket(ElectLeaderPacket packet) {
        if (packet.isHashed()) {
            hashedPackets.put(device.getAddress(), packet);
        } else {
            unhashedPackets.put(device.getAddress(), packet);
        }
    }

    private AdvertisePacket.Provides tieBreak() {
        BigInteger val = BigInteger.ONE;
        for (ElectLeaderPacket packet : unhashedPackets.values()) {
            BigInteger newval = new BigInteger(ElectLeaderPacket.uuidToBytes(packet.getTieBreak()));
            val = val.multiply(newval);
            val = newval;
        }
        byte[] hash = new byte[GenericHash.BYTES];
        LibsodiumInterface.getSodium().crypto_generichash(
                hash,
                hash.length,
                val.toByteArray(),
                val.toByteArray().length,
                null,
                0
        );

        BigInteger compare = new BigInteger(hash);
        AdvertisePacket.Provides ret = AdvertisePacket.Provides.INVALID; //for miracles

        for (ElectLeaderPacket packet : unhashedPackets.values()) {
            UUID uuid = packet.getLuid();
            if (uuid != null) {
                BigInteger c = new BigInteger(ElectLeaderPacket.uuidToBytes(uuid));
                if (c.abs().compareTo(compare.abs()) < 0) {
                    ret = packet.getProvides();
                    compare = c;
                }
            } else {
                Log.w("debug", "luid tag was null in tiebreak");
            }
        }
        return ret;
    }

    private Single<AdvertisePacket.Provides> countVotes() {

        return Single.fromCallable(() -> {
            final Map<AdvertisePacket.Provides, Integer> providesBuckets = new HashMap<>();
            for (ElectLeaderPacket packet : unhashedPackets.values()) {
                providesBuckets.putIfAbsent(packet.getProvides(), 0);
                providesBuckets.put(packet.getProvides(), providesBuckets.get(packet.getProvides())+1);
            }

            if (new HashSet<>(providesBuckets.values()).size() != providesBuckets.values().size()) {
                return tieBreak();
            }
            return Collections.max(providesBuckets.entrySet(), Map.Entry.comparingByValue()).getKey();
        });
    }

    public Single<AdvertisePacket.Provides> determineUpgrade() {
        return countVotes();
    }

    public Completable verifyPackets() {
        if (hashedPackets.size() != unhashedPackets.size()) {
            return Completable.error(new LuidStage.InvalidLuidException("size conflict"));
        }

        return Observable.zip(
                Observable.fromIterable(hashedPackets.values()),
                Observable.fromIterable(unhashedPackets.values()),
                ElectLeaderPacket::verifyHash
        )
                .flatMap(bool -> {
                    if (! bool) {
                        return Observable.error(new LuidStage.InvalidLuidException("failed to verify hash"));
                    } else {
                        return Observable.just(true);
                    }
                })
                .ignoreElements();
    }
}
