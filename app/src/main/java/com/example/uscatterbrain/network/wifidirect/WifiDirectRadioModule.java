package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pInfo;

import com.example.uscatterbrain.db.entities.HashlessScatterMessage;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.bluetoothLE.BootstrapRequest;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.CompletableSubject;
import io.reactivex.subjects.PublishSubject;

public interface WifiDirectRadioModule {
    String TAG = "WifiDirectRadioModule";
    Single<WifiP2pInfo> connectToGroup(String name, String passphrase, int timeout);
    Completable bootstrapFromUpgrade(
            BootstrapRequest upgradeRequest,
            Flowable<BlockDataStream> streamObservable
    );

    class BlockDataStream {
        private final Flowable<BlockSequencePacket> sequencePackets;
        private final BlockHeaderPacket headerPacket;
        private final ScatterMessage messageEntity;
        private final CompletableSubject sequenceCompletable = CompletableSubject.create();

        public BlockDataStream(BlockHeaderPacket headerPacket, Flowable<BlockSequencePacket> sequencePackets) {
            this.sequencePackets = sequencePackets
                    .doOnComplete(sequenceCompletable::onComplete)
                    .doOnError(sequenceCompletable::onError);
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
            this(message, packetFlowable, false);
        }

        public BlockDataStream(ScatterMessage message, Flowable<BlockSequencePacket> packetFlowable, boolean end) {
            BlockHeaderPacket.Builder builder = BlockHeaderPacket.newBuilder()
                    .setToFingerprint(message.message.to)
                    .setFromFingerprint(message.message.from)
                    .setApplication(message.message.application)
                    .setSig(message.message.sig)
                    .setToDisk(true) //TODO: handle this intelligently
                    .setSessionID(message.message.sessionid)
                    .setBlockSize(message.message.blocksize)
                    .setMime(message.message.mimeType)
                    .setExtension(message.message.extension)
                    .setHashes(HashlessScatterMessage.hashes2hash(message.messageHashes));

            if (end) {
                builder.setEndOfStream();
            }

            this.headerPacket = builder.build();
            this.messageEntity = message;
            this.sequencePackets = packetFlowable
                    .doOnComplete(sequenceCompletable::onComplete)
                    .doOnError(sequenceCompletable::onError);
        }

        public Completable awaitSequencePackets() {
            return sequenceCompletable;
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
