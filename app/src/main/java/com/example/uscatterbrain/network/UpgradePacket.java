package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wrapper class for protocol buffer upgrade message
 */
public class UpgradePacket implements ScatterSerializable {

    private ScatterProto.Upgrade mUpgrade;
    private int mSessionID;
    private ScatterProto.Advertise.Provides mProvides;

    private UpgradePacket(Builder builder) {
        this.mProvides = builder.getProvides();
        this.mSessionID = builder.getSessionID();
        this.mUpgrade = ScatterProto.Upgrade.newBuilder()
                .setProvides(mProvides)
                .setSessionid(mSessionID)
                .build();
    }

    private UpgradePacket(InputStream is) throws IOException {
        this.mUpgrade = ScatterProto.Upgrade.parseDelimitedFrom(is);
        this.mSessionID = this.mUpgrade.getSessionid();
        this.mProvides = this.mUpgrade.getProvides();
    }

    /**
     * Parse from upgrade packet.
     *
     * @param is the is
     * @return the upgrade packet
     */
    public static UpgradePacket parseFrom(InputStream is) {
        try {
            return new UpgradePacket(is);
        } catch (IOException e) {
            return null;
        }
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
    public boolean writeToStream(OutputStream os) {
        try {
            this.mUpgrade.writeTo(os);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mUpgrade;
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

            return new UpgradePacket(this);
        }
    }
}
