package net.ballmerlabs.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import net.ballmerlabs.uscatterbrain.network.AdvertisePacket;
import net.ballmerlabs.uscatterbrain.network.ElectLeaderPacket;
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class VotingStage {
    private final BluetoothDevice device;
    private final ArrayList<ElectLeaderPacket> hashedPackets = new ArrayList<>();
    private final ArrayList<ElectLeaderPacket> unhashedPackets = new ArrayList<>();
    private final UUID tiebreaker = UUID.randomUUID();

    public VotingStage(BluetoothDevice device) {
        this.device = device;
    }

    public ElectLeaderPacket getSelf(boolean hashed, AdvertisePacket.Provides provides) {
        ElectLeaderPacket.Builder builder = ElectLeaderPacket.newBuilder();

        if (hashed) {
            builder.enableHashing();
        }

        return builder
                .setProvides(provides)
                .setTiebreaker(tiebreaker)
                .build();
    }

    public void addPacket(ElectLeaderPacket packet) {
        if (packet.isHashed()) {
            hashedPackets.add(packet);
        } else {
            unhashedPackets.add(packet);
        }
    }


    private ElectLeaderPacket selectLeader() {
        BigInteger val = BigInteger.ONE;
        for (ElectLeaderPacket packet : unhashedPackets) {
            BigInteger newval = new BigInteger(ElectLeaderPacket.uuidToBytes(packet.getTieBreak()));
            val = val.multiply(newval);
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
        ElectLeaderPacket ret = null;

        for (ElectLeaderPacket packet : unhashedPackets) {
            UUID uuid = packet.getLuid();
            if (uuid != null) {
                BigInteger c = new BigInteger(ElectLeaderPacket.uuidToBytes(uuid));
                if (c.abs().compareTo(compare.abs()) < 0) {
                    ret = packet;
                    compare = c;
                }
            } else {
                Log.w("debug", "luid tag was null in tiebreak");
            }
        }

        if (ret == null) {
            throw new MiracleException();
        }
        return ret;
    }

    private AdvertisePacket.Provides tieBreak() {
        return selectLeader().getProvides();
    }

    public UUID selectSeme() {
        return selectLeader().getLuid();
    }

    private Single<AdvertisePacket.Provides> countVotes() {

        return Single.fromCallable(() -> {
            final Map<AdvertisePacket.Provides, Integer> providesBuckets = new HashMap<>();
            for (ElectLeaderPacket packet : unhashedPackets) {
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
                Observable.fromIterable(hashedPackets),
                Observable.fromIterable(unhashedPackets),
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

    // this is thrown in exceedingly rare cases if every device in the local mesh
    // has the same luid. This should only be thrown after the heat death of the universe
    public static class MiracleException extends RuntimeException {

    }
}
