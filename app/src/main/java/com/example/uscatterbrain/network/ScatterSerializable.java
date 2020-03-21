package com.example.uscatterbrain.network;

import com.google.protobuf.ByteString;

import java.io.OutputStream;

public interface ScatterSerializable {
    byte[] getBytes();
    ByteString getByteString();
    boolean writeToStream(OutputStream os);
}
