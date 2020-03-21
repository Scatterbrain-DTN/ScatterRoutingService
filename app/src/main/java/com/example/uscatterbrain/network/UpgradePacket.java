package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class UpgradePacket implements ScatterSerializable {

    private ScatterProto.Upgrade mUpgrade;
    private int mSessionID;
    private ScatterProto.Advertise.Provides mProvides;

    private UpgradePacket(Builder builder) {
        this.mProvides = builder.getProvies();
        this.mSessionID = builder.getSessionID();
        this.mUpgrade = ScatterProto.Upgrade.newBuilder()
                .setProvides(mProvides)
                .setSessionid(mSessionID)
                .build();
    }

    public UpgradePacket(InputStream is) throws IOException {
        this.mUpgrade = ScatterProto.Upgrade.parseDelimitedFrom(is);
        this.mSessionID = this.mUpgrade.getSessionid();
        this.mProvides = this.mUpgrade.getProvides();
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

    public int getSessionID() {
        return this.mSessionID;
    }

    public ScatterProto.Advertise.Provides getProvies() {
        return this.mProvides;
    }

    public static class Builder {
        private int mSessionid;
        private ScatterProto.Advertise.Provides mProvides;

        public Builder() {

        }

        public Builder setSessionID(int sessionID) {
            this.mSessionid = sessionID;
            return this;
        }

        public Builder setProvides(ScatterProto.Advertise.Provides provides) {
            this.mProvides = provides;
            return this;
        }

        public int getSessionID() {
            return this.mSessionid;
        }

        public ScatterProto.Advertise.Provides getProvies() {
            return this.mProvides;
        }

        public UpgradePacket build() {
            if (mProvides == null || mSessionid <= 0)
                return null;

            return new UpgradePacket(this);
        }
    }
}
