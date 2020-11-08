package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pInfo;

import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public interface WifiDirectRadioModule {
    String TAG = "WifiDirectRadioModule";
    String GROUP_NAME = "DIRECT-scattertest";
    String GROUP_PASSPHRASE = "youwillneverguessthis";
    String KEY_GROUP_NAME = "group-name";
    String KEY_GROUP_PASSPHRASE = "group-pass";
    Map<String,String> UPGRADE_METADATA = new HashMap<String, String>() {{
        put(KEY_GROUP_NAME, GROUP_NAME);
        put(KEY_GROUP_PASSPHRASE, GROUP_PASSPHRASE);
    }};
    Completable createGroup();
    Single<WifiP2pInfo> connectToGroup(String name, String passphrase);
    Observable<BlockDataStream> bootstrapFromUpgrade(
            BluetoothLEModule.UpgradeRequest upgradeRequest,
            Observable<BlockDataStream> streamObservable
    );

    class BlockDataStream {
        private final Flowable<BlockSequencePacket> sequencePackets;
        private final BlockHeaderPacket headerPacket;

        public BlockDataStream(BlockHeaderPacket headerPacket, Flowable<BlockSequencePacket> sequencePackets) {
            this.sequencePackets = sequencePackets;
            this.headerPacket = headerPacket;
        }

        public BlockHeaderPacket getHeaderPacket() {
            return headerPacket;
        }

        public Flowable<BlockSequencePacket> getSequencePackets() {
            return sequencePackets;
        }
    }
}
