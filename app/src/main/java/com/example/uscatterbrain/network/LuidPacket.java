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

public class LuidPacket implements ScatterSerializable {
    private final ScatterProto.Luid mLuid;
    private final boolean isHashed;
    private UUID luidtag;

    private LuidPacket(Builder builder) {
        this.isHashed = builder.enablehash;
        if (builder.enablehash) {
            mLuid = ScatterProto.Luid.newBuilder()
                    .setValHash(ByteString.copyFrom(calculateHashFromUUID(builder.uuid)))
                    .build();
        } else {
            ScatterProto.UUID u = protoUUIDfromUUID(builder.uuid);
            mLuid = ScatterProto.Luid.newBuilder()
                    .setValUuid(u)
                    .build();
        }
    }

    private LuidPacket(InputStream is) throws IOException {
        mLuid = CRCProtobuf.parseFromCRC(ScatterProto.Luid.parser(), is);
        isHashed = mLuid.getValCase() == ScatterProto.Luid.ValCase.VAL_HASH;
    }

    private static ScatterProto.UUID protoUUIDfromUUID(UUID uuid) {
        return ScatterProto.UUID.newBuilder()
                .setLower(uuid.getLeastSignificantBits())
                .setUpper(uuid.getMostSignificantBits())
                .build();
    }

    private static UUID protoUUIDtoUUID(ScatterProto.UUID uuid) {
        return new UUID(uuid.getUpper(), uuid.getLower());
    }

    private static byte[] calculateHashFromUUID(UUID uuid) {
        byte[] hashbytes = new byte[GenericHash.BYTES];
        ByteBuffer uuidBuffer = ByteBuffer.allocate(16);
        uuidBuffer.putLong(uuid.getMostSignificantBits());
        uuidBuffer.putLong(uuid.getLeastSignificantBits());
        byte[] uuidbytes = uuidBuffer.array();
        LibsodiumInterface.getSodium().crypto_generichash(
                hashbytes,
                hashbytes.length,
                uuidbytes,
                uuidbytes.length,
                null,
                0
        );

        return hashbytes;
    }

    public byte[] getHash() {
        if (isHashed) {
            return mLuid.getValHash().toByteArray();
        } else {
            return new byte[0];
        }
    }

    public UUID getHashAsUUID() {
        byte[] h = mLuid.getValHash().toByteArray();
        if (h.length != GenericHash.BYTES) {
            return null;
        } else if (isHashed) {
            ByteBuffer buf = ByteBuffer.wrap(h);
            //note: this only is safe because crypto_generichash_BYTES_MIN is 16
            return new UUID(buf.getLong(), buf.getLong());
        } else {
            return null;
        }
    }

    public boolean verifyHash(LuidPacket packet) {
        if (packet.isHashed == this.isHashed) {
            return false;
        } else if (this.isHashed) {
            byte[] hash = calculateHashFromUUID(packet.getLuid());
            return Arrays.equals(hash, getHash());
        } else {
            byte[] hash = calculateHashFromUUID(this.getLuid());
            return Arrays.equals(hash, packet.getHash());
        }
    }

    public static Single<LuidPacket> parseFrom(InputStream inputStream) {
        return Single.fromCallable(() -> new LuidPacket(inputStream));
    }

    public static Single<LuidPacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return LuidPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static Single<LuidPacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return LuidPacket.parseFrom(observer).doFinally(observer::close);
    }

    public ScatterProto.Luid.ValCase getValCase() {
        return mLuid.getValCase();
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            CRCProtobuf.writeToCRC(mLuid, os);
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
        return Completable.fromAction(() -> CRCProtobuf.writeToCRC(mLuid, os));
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mLuid;
    }

    @Override
    public Flowable<byte[]> writeToStream(int fragsize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragsize);
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_LUID;
    }

    @Override
    public void tagLuid(UUID luid) {
        luidtag = luid;
    }

    public boolean isHashed() {
        return isHashed;
    }

    public UUID getLuid() {
        return protoUUIDtoUUID(mLuid.getValUuid());
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private UUID uuid;
        private boolean enablehash;

        private Builder() {
            enablehash = false;
        }

        public Builder enableHashing() {
            this.enablehash = true;
            return this;
        }

        public Builder setLuid(UUID uuid) {
            this.uuid = uuid;
            return this;
        }

        public LuidPacket build() {
            if (uuid == null) {
                throw new IllegalArgumentException("uuid required");
            }

            return new LuidPacket(this);
        }
    }
}
