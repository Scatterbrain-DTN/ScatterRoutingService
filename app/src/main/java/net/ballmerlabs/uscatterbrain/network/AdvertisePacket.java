package net.ballmerlabs.uscatterbrain.network;

import net.ballmerlabs.uscatterbrain.ScatterProto;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

/**
 * Wrapper class for advertisepacket protocol buffer message.
 */
public class AdvertisePacket implements ScatterSerializable {
    private final ScatterProto.Advertise mAdvertise;
    private final List<Provides> mProvides;
    public enum Provides {
        INVALID(-1),
        BLE(0),
        WIFIP2P(1);

        private int val;

        Provides(int val) {
            this.val = val;
        }

        public int getVal() {
            return val;
        }
    }

    private UUID luidtag;

    public static List<Integer> providesToValArray(List<Provides> provides) {
        final List<Integer> res = new ArrayList<>();
        for (Provides p : provides) {
            res.add(p.getVal());
        }
        return res;
    }

    public static List<Provides> valToProvidesArray(List<Integer> vals) {
        final ArrayList<Provides> provides = new ArrayList<>();
        for (Integer i : vals) {
            for (Provides p : Provides.values()) {
                if (p.getVal() == i) {
                    provides.add(p);
                }
            }
        }
        return provides;
    }

    public static Provides valToProvides(int val) {
        for (Provides p : Provides.values()) {
            if (p.getVal() == val) {
                return p;
            }
        }
        return Provides.INVALID;
    }

    public static int providesToVal(Provides provides) {
        return provides.getVal();
    }

    private AdvertisePacket(Builder builder) {
        mProvides = builder.getProvides();
        this.mAdvertise = ScatterProto.Advertise.newBuilder()
                .addAllProvides(providesToValArray(mProvides))
                .build();
    }

    private AdvertisePacket(InputStream is) throws IOException {
        mAdvertise =  CRCProtobuf.parseFromCRC(ScatterProto.Advertise.parser(), is);
        mProvides = valToProvidesArray(mAdvertise.getProvidesList());
    }

    /**
     * Parse from advertise packet.
     *
     * @param is the is
     * @return the advertise packet
     */
    public static Single<AdvertisePacket> parseFrom(InputStream is) {
        return Single.fromCallable(() -> new AdvertisePacket(is));
    }

    public static Single<AdvertisePacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return parseFrom(observer).doFinally(observer::close);
    }

    public static Single<AdvertisePacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return parseFrom(observer).doFinally(observer::close);
    }

    /**
     * Gets provides.
     *
     * @return the provides
     */
    public List<Provides> getProvides() {
        return mProvides;
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            CRCProtobuf.writeToCRC(mAdvertise, os);
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
        return Completable.fromAction(() -> CRCProtobuf.writeToCRC(mAdvertise, os));
    }

    @Override
    public Flowable<byte[]> writeToStream(int fragsize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragsize);
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mAdvertise;
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_ADVERTISE;
    }

    @Override
    public void tagLuid(UUID luid) {
        luidtag = luid;
    }

    @Override
    public UUID getLuid() {
        return luidtag;
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
        private List<Provides> mProvides;

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
        public  Builder setProvides(List<Provides> provides) {
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
        public List<Provides> getProvides() {
            return mProvides;
        }

    }
}
