package net.ballmerlabs.uscatterbrain.network;

import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import net.ballmerlabs.uscatterbrain.ScatterProto;

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

public class DeclareHashesPacket implements ScatterSerializable {
    private final ScatterProto.DeclareHashes declareHashes;
    private UUID luid;

    private DeclareHashesPacket(InputStream inputStream) throws IOException {
        this.declareHashes = CRCProtobuf.parseFromCRC(ScatterProto.DeclareHashes.parser(), inputStream);
    }

    private DeclareHashesPacket(Builder builder) {
       declareHashes = ScatterProto.DeclareHashes.newBuilder()
               .setOptout(builder.optout)
               .addAllHashes(builder.hashes)
               .build();
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            CRCProtobuf.writeToCRC(declareHashes, os);
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
        return Completable.fromAction(() -> CRCProtobuf.writeToCRC(declareHashes, os));
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return declareHashes;
    }

    @Override
    public Flowable<byte[]> writeToStream(int fragsize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragsize);
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_DECLARE_HASHES;
    }

    @Override
    public void tagLuid(UUID luid) {
        this.luid = luid;
    }

    @Override
    public UUID getLuid() {
        return luid;
    }

    public List<byte[]> getHashes() {
        final ArrayList<byte[]> r = new ArrayList<>();
        for (final ByteString b : declareHashes.getHashesList()) {
            r.add(b.toByteArray());
        }
        return r;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static Single<DeclareHashesPacket> parseFrom(InputStream inputStream) {
        return Single.fromCallable(() -> new DeclareHashesPacket(inputStream));
    }

    public static Single<DeclareHashesPacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return DeclareHashesPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static Single<DeclareHashesPacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return DeclareHashesPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static class Builder {
        private List<ByteString> hashes;
        private boolean optout;

        private Builder() {
            optout = false;
        }

        public Builder setHashes(List<ByteString> hashes) {
            this.hashes = hashes;
            return this;
        }

        public Builder setHashesByte(List<byte[]> hashes) {
            this.hashes = new ArrayList<>();
            for (byte[] bytes : hashes) {
                this.hashes.add(ByteString.copyFrom(bytes));
            }
            return this;
        }

        public Builder optOut() {
            this.optout = true;
            return this;
        }

        public DeclareHashesPacket build() {
            if (this.hashes == null) {
                this.hashes = new ArrayList<>();
            }

            return new DeclareHashesPacket(this);
        }
    }
}
