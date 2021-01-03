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
            messageEntity.to = headerPacket.getToFingerprint().toByteArray();
            messageEntity.from = headerPacket.getFromFingerprint().toByteArray();
            messageEntity.application = headerPacket.getApplication();
            messageEntity.sig = headerPacket.getSignature();
            messageEntity.sessionid = headerPacket.getSessionID();
            messageEntity.blocksize = headerPacket.getBlockSize();
            messageEntity.mimeType =  headerPacket.getMime();
            messageEntity.hashes = ScatterMessage.hash2hashs(headerPacket.getHashList());
        }

        public BlockDataStream(ScatterMessage message, Flowable<BlockSequencePacket> packetFlowable) {
            this(BlockHeaderPacket.newBuilder()
                    .setToFingerprint(ByteString.copyFrom(message.to))
                    .setFromFingerprint(ByteString.copyFrom(message.from))
                    .setApplication(message.application)
                    .setSig(ByteString.copyFrom(message.sig))
                    .setToDisk(true) //TODO: handle this intelligently
                    .setSessionID(message.sessionid)
                    .setBlockSize(message.blocksize)
                    .setMime(message.mimeType)
                    .setHashes(ScatterMessage.hashes2hash(message.hashes))
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
