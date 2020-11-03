package com.example.uscatterbrain.network;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import java.io.OutputStream;

import io.reactivex.Completable;
import io.reactivex.Flowable;

public interface ScatterSerializable {
    byte[] getBytes();
    ByteString getByteString();
    Completable writeToStream(OutputStream os);
    GeneratedMessageLite getMessage();
    Flowable<byte[]> writeToStream();
}
