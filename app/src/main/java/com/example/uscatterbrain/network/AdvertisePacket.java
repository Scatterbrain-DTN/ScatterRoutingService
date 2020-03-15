package com.example.uscatterbrain.network;

import com.example.uscatterbrain.API.HighLevelAPI;
import com.example.uscatterbrain.DeviceProfile;
import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class AdvertisePacket implements ScatterSerializable {
    private ScatterProto.Advertise mAdvertise;
    private List<ScatterProto.Advertise.Provides> mProvides;

    private AdvertisePacket(Builder builder) {
        this.mProvides = builder.getProvides();
        this.mAdvertise = ScatterProto.Advertise.newBuilder()
                .addAllProvides(mProvides)
                .build();
    }

    public AdvertisePacket(InputStream is) throws IOException {
        mAdvertise =  ScatterProto.Advertise.parseFrom(is);
        this.mProvides = mAdvertise.getProvidesList();
    }

    public List<ScatterProto.Advertise.Provides> getProvides() {
        return mProvides;
    }

    @Override
    public byte[] getBytes() {
        return mAdvertise.toByteArray();
    }

    @Override
    public ByteString getByteString() {
        return mAdvertise.toByteString();
    }

    @Override
    public boolean writeToStream(OutputStream os) {
        try {
            mAdvertise.writeTo(os);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    @Override
    public int size() {
        return mAdvertise.toByteString().size();
    }

    public static class Builder {
        private List<ScatterProto.Advertise.Provides> mProvides;

        public Builder() {

        }

        public  Builder setProvides(List<ScatterProto.Advertise.Provides> provides) {
            this.mProvides = provides;
            return this;
        }

        public AdvertisePacket build() {
            if (this.mProvides == null)
                return null;

            return new AdvertisePacket(this);
        }

        public List<ScatterProto.Advertise.Provides> getProvides() {
            return mProvides;
        }

    }
}
