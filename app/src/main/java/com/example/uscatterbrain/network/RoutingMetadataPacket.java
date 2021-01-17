package com.example.uscatterbrain.network;

import android.app.PendingIntent;

import com.example.uscatterbrain.ScatterProto;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import com.goterl.lazycode.lazysodium.interfaces.Hash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class RoutingMetadataPacket implements ScatterSerializable {
    private final ScatterProto.RoutingMetadata routingMetadata;
    private final HashMap<UUID, byte[]> metadataMap = new HashMap<>();

    private UUID luid;
    private RoutingMetadataPacket(InputStream inputStream) throws IOException {
        this.routingMetadata = ScatterProto.RoutingMetadata.parseDelimitedFrom(inputStream);
        addMap(this.routingMetadata.getKeyvalMap());
    }

    private RoutingMetadataPacket(Builder builder) {
        routingMetadata = ScatterProto.RoutingMetadata.newBuilder()
                .putAllKeyval(getMap())
                .setId(ScatterProto.UUID.newBuilder()
                        .setLower(builder.uuid.getLeastSignificantBits())
                        .setUpper(builder.uuid.getMostSignificantBits()))
                .build();
    }

    private synchronized void addMap(Map<String, ByteString> val) {
        metadataMap.clear();
        for (Map.Entry<String, ByteString> entry : val.entrySet()) {
            metadataMap.put(UUID.fromString(entry.getKey()), entry.getValue().toByteArray());
        }
    }

    private synchronized Map<String, ByteString> getMap() {
        final Map<String, ByteString> result = new HashMap<>();
        for (Map.Entry<UUID, byte[]> entry : metadataMap.entrySet()) {
            result.put(entry.getKey().toString(), ByteString.copyFrom(entry.getValue()));
        }
        return result;
    }

    public Map<UUID, byte[]> getMetadata() {
        return metadataMap;
    }

    public UUID getUUID() {
        return new UUID(routingMetadata.getId().getUpper(), routingMetadata.getId().getLower());
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            routingMetadata.writeDelimitedTo(os);
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
        return Completable.fromAction(() -> routingMetadata.writeDelimitedTo(os));
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return routingMetadata;
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

    public static Builder newBuilder() {
        return new Builder();
    }

    public static Single<RoutingMetadataPacket> parseFrom(InputStream inputStream) {
        return Single.fromCallable(() -> new RoutingMetadataPacket(inputStream));
    }

    public static Single<RoutingMetadataPacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return RoutingMetadataPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static Single<RoutingMetadataPacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return RoutingMetadataPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static class Builder {
        private Map<UUID, byte[]> map = new HashMap<>();
        private boolean empty;
        private UUID uuid;

        private Builder() {
            empty = false;
        }

        public Builder addMetadata(Map<UUID, byte[]> map) {
            this.map.putAll(map);
            return this;
        }

        public Builder setUUID(UUID uuid) {
            this.uuid = uuid;
            return this;
        }

        public RoutingMetadataPacket build() {
            if (this.uuid == null || !empty) {
                throw new IllegalArgumentException("uuid must be set");
            }

            return new RoutingMetadataPacket(this);
        }
    }
}
