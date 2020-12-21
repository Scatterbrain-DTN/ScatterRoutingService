package com.example.uscatterbrain.network;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import java.io.OutputStream;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Flowable;

public interface ScatterSerializable {
    enum PacketType {
        TYPE_ACK,
        TYPE_BLOCKSEQUENCE,
        TYPE_BLOCKHEADER,
        TYPE_IDENTITY,
        TYPE_ADVERTISE,
        TYPE_UPGRADE,
        TYPE_ELECT_LEADER,
        TYPE_LUID
    }
    byte[] getBytes();
    ByteString getByteString();
    Completable writeToStream(OutputStream os);
    GeneratedMessageLite getMessage();
    Flowable<byte[]> writeToStream(int fragsize);
    PacketType getType();
    void tagLuid(UUID luid);
    UUID getLuid();
}
