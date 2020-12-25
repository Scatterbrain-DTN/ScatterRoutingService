package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class ElectLeaderPacket implements ScatterSerializable {
    private final ScatterProto.ElectLeader mElectLeader;
    private final byte[] salt;
    private UUID luidtag;
    public enum Phase {
        PHASE_VAL,
        PHASE_HASH
    }

    private ElectLeaderPacket(Builder builder) {
        salt = new byte[GenericHash.BYTES];
        LibsodiumInterface.getSodium().randombytes_buf(salt, salt.length);
        ScatterProto.ElectLeader.Builder b = ScatterProto.ElectLeader.newBuilder();
        if (!builder.enableHashing) {
            ScatterProto.ElectLeader.Body.Builder body = ScatterProto.ElectLeader.Body.newBuilder()
                    .setProvides(AdvertisePacket.providesToVal(builder.provides))
                    .setSalt(ByteString.copyFrom(salt));

            body.setTiebreakerVal(ScatterProto.UUID.newBuilder()
                    .setUpper(builder.tiebreaker.getMostSignificantBits())
                    .setLower(builder.tiebreaker.getLeastSignificantBits())
                    .build());


            b.setValBody(body.build());
        } else {
            b.setValHash(ByteString.copyFrom(hashFromBuilder(builder)));
        }

        mElectLeader = b.build();
    }


    private byte[] hashFromBuilder(Builder builder) {
        byte[] hashbytes = new byte[GenericHash.BYTES];
        ByteString bytes = ByteString.EMPTY;

        bytes.concat(ByteString.copyFrom(salt));
;
        bytes = bytes.concat(ByteString.copyFrom(uuidToBytes(builder.tiebreaker)));

        ByteBuffer buffer = ByteBuffer.allocate(Integer.SIZE);

        buffer.putInt(builder.provides.getVal());
        bytes.concat(ByteString.copyFrom(buffer.array()));

        LibsodiumInterface.getSodium().crypto_generichash(
                hashbytes,
                hashbytes.length,
                bytes.toByteArray(),
                bytes.toByteArray().length,
                null,
                0
        );

        return hashbytes;
    }

    public static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer uuidBuffer = ByteBuffer.allocate(16);
        uuidBuffer.putLong(uuid.getMostSignificantBits());
        uuidBuffer.putLong(uuid.getLeastSignificantBits());
        return uuidBuffer.array();
    }

    public byte[] hashFromPacket() {
        byte[] hashbytes = new byte[GenericHash.BYTES];
        ByteString bytes = ByteString.EMPTY;

        bytes.concat(ByteString.copyFrom(salt));

        bytes = bytes.concat(ByteString.copyFrom(uuidToBytes(getTieBreak())));

        ByteBuffer buffer = ByteBuffer.allocate(Integer.SIZE);

        buffer.putInt(mElectLeader.getValBody().getProvides());
        bytes.concat(ByteString.copyFrom(buffer.array()));

        LibsodiumInterface.getSodium().crypto_generichash(
                hashbytes,
                hashbytes.length,
                bytes.toByteArray(),
                bytes.toByteArray().length,
                null,
                0
        );

        return hashbytes;
    }

    public boolean verifyHash(ElectLeaderPacket packet) {
        if (packet.isHashed() == this.isHashed()) {
            return false;
        } else if (this.isHashed()) {
            byte[] hash =  packet.hashFromPacket();
            return Arrays.equals(hash, getHash());
        } else {
            byte[] hash = hashFromPacket();
            return Arrays.equals(hash, packet.getHash());
        }
    }

    private ElectLeaderPacket(InputStream inputStream) throws IOException {
        this.mElectLeader = ScatterProto.ElectLeader.parseDelimitedFrom(inputStream);
        if (this.mElectLeader.getValCase().compareTo(ScatterProto.ElectLeader.ValCase.VAL_BODY) == 0) {
            this.salt = mElectLeader.getValBody().getSalt().toByteArray();
        } else {
            this.salt = new byte[0];
        }
    }

    public UUID getTieBreak() {
        return new UUID(
                mElectLeader.getValBody().getTiebreakerVal().getUpper(),
                mElectLeader.getValBody().getTiebreakerVal().getLower()
        );
    }

    @Override
    public void tagLuid(UUID luid) {
        luidtag = luid;
    }

    @Override
    public UUID getLuid() {
        return luidtag;
    }

    public AdvertisePacket.Provides getProvides() {
        return AdvertisePacket.valToProvides(mElectLeader.getValBody().getProvides());
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
    public Flowable<byte[]> writeToStream(int fragize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragize);
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_ELECT_LEADER;
    }

    public boolean isHashed() {
        return mElectLeader.getValCase() == ScatterProto.ElectLeader.ValCase.VAL_HASH;
    }

    public byte[] getHash() {
        return mElectLeader.getValHash().toByteArray();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {

        private boolean enableHashing = false;
        private AdvertisePacket.Provides provides;
        private UUID tiebreaker;

        private Builder() {
        }

        public Builder enableHashing() {
            this.enableHashing = true;
            return this;
        }

        public Builder setProvides(AdvertisePacket.Provides provides) {
            this.provides = provides;
            return this;
        }


        public Builder setTiebreaker(UUID tiebreaker) {
            this.tiebreaker =tiebreaker;
            return this;
        }

        public ElectLeaderPacket build() {
            if ( provides == null || tiebreaker == null) {
                throw new IllegalArgumentException("both tiebreaker and provides must be set");
            }

            return new ElectLeaderPacket(this);
        }
    }
}
