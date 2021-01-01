package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pInfo;

import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.bluetoothLE.BootstrapRequest;
import com.google.protobuf.ByteString;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public interface WifiDirectRadioModule {
    String TAG = "WifiDirectRadioModule";
    Single<WifiP2pInfo> connectToGroup(String name, String passphrase, int timeout);
    Observable<BlockDataStream> bootstrapFromUpgrade(
            BootstrapRequest upgradeRequest,
            Observable<BlockDataStream> streamObservable
    );

    class BlockDataStream {
        private final Flowable<BlockSequencePacket> sequencePackets;
        private final BlockHeaderPacket headerPacket;
        private final ScatterMessage messageEntity = new ScatterMessage();

        public BlockDataStream(BlockHeaderPacket headerPacket, Flowable<BlockSequencePacket> sequencePackets) {
            this.sequencePackets = sequencePackets;
            this.headerPacket = headerPacket;
            messageEntity.setTo(headerPacket.getToFingerprint().toByteArray());
            messageEntity.setFrom(headerPacket.getFromFingerprint().toByteArray());
            messageEntity.setApplication(headerPacket.getApplication());
            messageEntity.setSig(headerPacket.getSignature());
            messageEntity.setSessionid(headerPacket.getSessionID());
            messageEntity.setBlocksize(headerPacket.getBlockSize());
            messageEntity.setFilePath(headerPacket.getFilename());
            messageEntity.setHashes(ScatterMessage.hash2hashs(headerPacket.getHashList()));
        }

        public BlockDataStream(ScatterMessage message, Flowable<BlockSequencePacket> packetFlowable) {
            this(BlockHeaderPacket.newBuilder()
                    .setToFingerprint(ByteString.copyFrom(message.getTo()))
                    .setFromFingerprint(ByteString.copyFrom(message.getFrom()))
                    .setApplication(message.getApplication())
                    .setSig(ByteString.copyFrom(message.getSig()))
                    .setToDisk(true)
                    .setSessionID(message.getSessionid())
                    .setBlockSize(message.getBlocksize())
                    .setHashes(ScatterMessage.hashes2hash(message.getHashes()))
                    .build(), packetFlowable);
        }

        public BlockHeaderPacket getHeaderPacket() {
            return headerPacket;
        }

        public Flowable<BlockSequencePacket> getSequencePackets() {
            return sequencePackets;
        }

        public ScatterMessage getEntity() {
            return messageEntity;
        }
    }
}
