package net.ballmerlabs.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pInfo;

import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage;
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage;
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket;
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.subjects.CompletableSubject;

public interface WifiDirectRadioModule {
    String TAG = "WifiDirectRadioModule";
    Single<WifiP2pInfo> connectToGroup(String name, String passphrase, int timeout);
    Completable bootstrapFromUpgrade(BootstrapRequest upgradeRequest);
    void unregisterReceiver();
    void registerReceiver();

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
            if (this.headerPacket == null) {
                throw new IllegalStateException("header packet was null");
            }
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
            messageEntity.message.globalhash = ScatterbrainDatastore.getGlobalHash(headerPacket.getHashList());
            messageEntity.messageHashes = HashlessScatterMessage.hash2hashs(headerPacket.getHashList());
        }

        public Completable await() {
            return sequencePackets.ignoreElements();
        }

        public BlockDataStream(ScatterMessage message, Flowable<BlockSequencePacket> packetFlowable) {
            this(message, packetFlowable, false, true);
        }

        public boolean getToDisk() {
            return headerPacket.getToDisk();
        }

        public BlockDataStream(ScatterMessage message, Flowable<BlockSequencePacket> packetFlowable, boolean end, boolean todisk) {
            BlockHeaderPacket.Builder builder = BlockHeaderPacket.newBuilder()
                    .setToFingerprint(message.message.to)
                    .setFromFingerprint(message.message.from)
                    .setApplication(message.message.application)
                    .setSig(message.message.sig)
                    .setToDisk(todisk)
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
            if (this.headerPacket == null) {
                throw new IllegalStateException("header packet was null");
            }
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
