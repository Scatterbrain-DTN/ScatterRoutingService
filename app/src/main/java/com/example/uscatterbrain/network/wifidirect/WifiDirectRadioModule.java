package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pInfo;

import com.example.uscatterbrain.db.entities.HashlessScatterMessage;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.bluetoothLE.BootstrapRequest;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public interface WifiDirectRadioModule {
    String TAG = "WifiDirectRadioModule";
    Single<WifiP2pInfo> connectToGroup(String name, String passphrase, int timeout);
    Observable<BlockDataStream> bootstrapFromUpgrade(
            BootstrapRequest upgradeRequest,
            Flowable<BlockDataStream> streamObservable
    );

    class BlockDataStream {
        private final Flowable<BlockSequencePacket> sequencePackets;
        private final BlockHeaderPacket headerPacket;
        private final ScatterMessage messageEntity;

        public BlockDataStream(BlockHeaderPacket headerPacket, Flowable<BlockSequencePacket> sequencePackets) {
            this.sequencePackets = sequencePackets;
            this.headerPacket = headerPacket;
            messageEntity = new ScatterMessage();
            messageEntity.message = new HashlessScatterMessage();
            messageEntity.message.to = headerPacket.getToFingerprint().toByteArray();
            messageEntity.message.from = headerPacket.getFromFingerprint().toByteArray();
            messageEntity.message.application = headerPacket.getApplication();
            messageEntity.message.sig = headerPacket.getSignature();
            messageEntity.message.sessionid = headerPacket.getSessionID();
            messageEntity.message.blocksize = headerPacket.getBlockSize();
            messageEntity.message.mimeType =  headerPacket.getMime();
            messageEntity.message.extension = headerPacket.getExtension();
            messageEntity.messageHashes = HashlessScatterMessage.hash2hashs(headerPacket.getHashList());
        }

        public BlockDataStream(ScatterMessage message, Flowable<BlockSequencePacket> packetFlowable) {
            headerPacket = BlockHeaderPacket.newBuilder()
                    .setToFingerprint(message.message.to)
                    .setFromFingerprint(message.message.from)
                    .setApplication(message.message.application)
                    .setSig(message.message.sig)
                    .setToDisk(true) //TODO: handle this intelligently
                    .setSessionID(message.message.sessionid)
                    .setBlockSize(message.message.blocksize)
                    .setMime(message.message.mimeType)
                    .setExtension(message.message.extension)
                    .setHashes(HashlessScatterMessage.hashes2hash(message.messageHashes))
                    .build();
            this.messageEntity = message;
            this.sequencePackets = packetFlowable;
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
