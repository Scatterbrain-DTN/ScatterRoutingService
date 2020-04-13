package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Wrapper class for advertisepacket protocol buffer message.
 */
public class AdvertisePacket implements ScatterSerializable {
    private ScatterProto.Advertise mAdvertise;
    private List<ScatterProto.Advertise.Provides> mProvides;

    private AdvertisePacket(Builder builder) {
        this.mProvides = builder.getProvides();
        this.mAdvertise = ScatterProto.Advertise.newBuilder()
                .addAllProvides(mProvides)
                .build();
    }

    private AdvertisePacket(InputStream is) throws IOException {
        mAdvertise =  ScatterProto.Advertise.parseDelimitedFrom(is);
        this.mProvides = mAdvertise.getProvidesList();
    }

    /**
     * Parse from advertise packet.
     *
     * @param is the is
     * @return the advertise packet
     */
    public static AdvertisePacket parseFrom(InputStream is) {
        try {
            return new AdvertisePacket(is);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Gets provides.
     *
     * @return the provides
     */
    public List<ScatterProto.Advertise.Provides> getProvides() {
        return mProvides;
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            this.mAdvertise.writeDelimitedTo(os);
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
            mAdvertise.writeDelimitedTo(os);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mAdvertise;
    }

    /**
     * New builder class
     *
     * @return the builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * builder for advertise packet
     */
    public static class Builder {
        private List<ScatterProto.Advertise.Provides> mProvides;

        /**
         * Instantiates a new Builder.
         */
        public Builder() {

        }

        /**
         * Sets provides.
         *
         * @param provides scatterbrain provides enum
         * @return builder
         */
        public  Builder setProvides(List<ScatterProto.Advertise.Provides> provides) {
            this.mProvides = provides;
            return this;
        }

        /**
         * Build advertise packet.
         *
         * @return the advertise packet
         */
        public AdvertisePacket build() {
            if (this.mProvides == null)
                return null;

            return new AdvertisePacket(this);
        }

        /**
         * Gets provides.
         *
         * @return the provides
         */
        public List<ScatterProto.Advertise.Provides> getProvides() {
            return mProvides;
        }

    }
}
