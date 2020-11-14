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

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class ElectLeaderPacket implements ScatterSerializable {
    private final ScatterProto.ElectLeader mElectLeader;
    private final Phase mPhase;
    public enum Phase {
        PHASE_VAL,
        PHASE_HASH
    }

    private ElectLeaderPacket(Builder builder) {
        ScatterProto.ElectLeader.Builder b = ScatterProto.ElectLeader.newBuilder();
        if (builder.valset) {
            mPhase = Phase.PHASE_VAL;
            b.setValInt(builder.val);
        } else {
            mPhase = Phase.PHASE_HASH;
            b.setValHash(ByteString.copyFrom(builder.hash));
        }

        mElectLeader = b.build();
    }

    private ElectLeaderPacket(InputStream inputStream) throws IOException {
        this.mElectLeader = ScatterProto.ElectLeader.parseDelimitedFrom(inputStream);
        if (this.mElectLeader.getValCase().getNumber() == ScatterProto.ElectLeader.VAL_INT_FIELD_NUMBER) {
            this.mPhase = Phase.PHASE_VAL;
        } else {
            this.mPhase = Phase.PHASE_HASH;
        }
    }

    public static Single<ElectLeaderPacket> parseFrom(InputStream inputStream) {
        return Single.fromCallable(() -> new ElectLeaderPacket(inputStream));
    }

    public static Single<ElectLeaderPacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return ElectLeaderPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static Single<ElectLeaderPacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return ElectLeaderPacket.parseFrom(observer).doFinally(observer::close);
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            mElectLeader.writeDelimitedTo(os);
            return os.toByteArray();
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    public ByteString getByteString() {
        return ByteString.copyFrom(getBytes());
    }

    @Override
    public Completable writeToStream(OutputStream os) {
        return Completable.fromAction(() -> mElectLeader.writeDelimitedTo(os));
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mElectLeader;
    }

    @Override
    public Flowable<byte[]> writeToStream() {
        return Bytes.from(new ByteArrayInputStream(getBytes()));
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_ELECT_LEADER;
    }


    public Phase getPhase() {
        return mPhase;
    }

    public long getVal() {
        return mElectLeader.getValInt();
    }

    public byte[] getHash() {
        return mElectLeader.getValHash().toByteArray();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private long val;
        private byte[] hash;
        private boolean valset;

        private Builder() {
            valset = false;
        }

        public Builder setVal(long val) {
            this.val = val;
            valset = true;
            return this;
        }

        public Builder setHash(byte[] hash) {
            this.hash = hash;
            return this;
        }

        public ElectLeaderPacket build() {
            if ( hash != null && valset) {
                throw new IllegalArgumentException("set only one of val or luid");
            }

            return new ElectLeaderPacket(this);
        }
    }
}
