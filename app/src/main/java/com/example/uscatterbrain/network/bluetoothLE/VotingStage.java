package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;

import com.example.uscatterbrain.network.ElectLeaderPacket;

import java.util.HashMap;

import io.reactivex.Completable;
import io.reactivex.Observable;

public class VotingStage {
    private final BluetoothDevice device;
    private final HashMap<String, ElectLeaderPacket> votePackets = new HashMap<>();
    private final HashMap<String, ElectLeaderPacket> tiebreakPackets = new HashMap<>();

    public VotingStage(BluetoothDevice device) {
        this.device = device;
    }

    public void addPacket(ElectLeaderPacket packet) {
        if (packet.isHashed()) {
            votePackets.put(device.getAddress(), packet);
        } else {
            tiebreakPackets.put(device.getAddress(), packet);
        }
    }

    public Completable verifyPackets() {
        if (votePackets.size() != tiebreakPackets.size()) {
            return Completable.error(new LuidStage.InvalidLuidException("size conflict"));
        }

        return Observable.zip(
                Observable.fromIterable(votePackets.values()),
                Observable.fromIterable(tiebreakPackets.values()),
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
