package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

/**
 * Wrapper class for protocol buffer upgrade message
 */
public class UpgradePacket implements ScatterSerializable {

    private final ScatterProto.Upgrade mUpgrade;
    private final int mSessionID;
    private final Map<String,String> mMetadata;
    private final ScatterProto.Advertise.Provides mProvides;

    private UpgradePacket(Builder builder) {
        this.mProvides = builder.getProvides();
        this.mSessionID = builder.getSessionID();
        this.mMetadata = builder.metadata;
        this.mUpgrade = ScatterProto.Upgrade.newBuilder()
                .setProvides(mProvides)
                .setSessionid(mSessionID)
                .putAllMetadata(mMetadata)
                .build();
    }

    private UpgradePacket(InputStream is) throws IOException {
        this.mUpgrade = ScatterProto.Upgrade.parseDelimitedFrom(is);
        this.mSessionID = this.mUpgrade.getSessionid();
        this.mProvides = this.mUpgrade.getProvides();
        this.mMetadata = this.mUpgrade.getMetadataMap();
    }

    /**
     * Parse from upgrade packet.
     *
     * @param is the is
     * @return the upgrade packet
     */
    public static Single<UpgradePacket> parseFrom(InputStream is) {
        return Single.fromCallable(() -> new UpgradePacket(is));
    }

    public static Single<UpgradePacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return UpgradePacket.parseFrom(observer).doFinally(observer::close);
    }

    public static Single<UpgradePacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return UpgradePacket.parseFrom(observer).doFinally(observer::close);
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            mUpgrade.writeDelimitedTo(os);
            return os.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public ByteString getByteString() {
        return ByteString.copyFrom(getBytes());
    }

    @Override
    public Completable writeToStream(OutputStream os) {
        return Completable.fromAction(() -> mUpgrade.writeDelimitedTo(os));
    }

    @Override
    public Flowable<byte[]> writeToStream(int fragsize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragsize);
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mUpgrade;
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_UPGRADE;
    }

    /**
     * Gets session id.
     *
     * @return the session id
     */
    public int getSessionID() {
        return this.mSessionID;
    }


    /**
     * gets the metadata map
     */
    public Map<String, String> getMetadata() {
        return mMetadata;
    }

    /**
     * Gets provides.
     *
     * @return the provies
     */
    public ScatterProto.Advertise.Provides getProvides() {
        return this.mProvides;
    }

    /**
     * Constructs a new builder class.
     *
     * @return the builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * The type Builder.
     */
    public static class Builder {
        private int mSessionid;
        private ScatterProto.Advertise.Provides mProvides;
        private Map<String, String> metadata;

        /**
         * Sets session id.
         *
         * @param sessionID the session id
         * @return builder
         */
        public Builder setSessionID(int sessionID) {
            this.mSessionid = sessionID;
            return this;
        }

        /**
         * Sets provides.
         *
         * @param provides the provides
         * @return builder
         */
        public Builder setProvides(ScatterProto.Advertise.Provides provides) {
            this.mProvides = provides;
            return this;
        }


        public Builder setMetadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Gets session id.
         *
         * @return the session id
         */
        public int getSessionID() {
            return this.mSessionid;
        }

        /**
         * Gets provies.
         *
         * @return provides
         */
        public ScatterProto.Advertise.Provides getProvides() {
            return this.mProvides;
        }

        /**
         * Build upgrade packet.
         *
         * @return the upgrade packet
         */
        public UpgradePacket build() {
            if (mProvides == null || mSessionid <= 0)
                return null;

            if (metadata == null) {
                metadata = new HashMap<>();
            }
            return new UpgradePacket(this);
        }
    }
}
