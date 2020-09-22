package com.example.uscatterbrain.network;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import java.io.OutputStream;

public interface ScatterSerializable {
    byte[] getBytes();
    ByteString getByteString();
    boolean writeToStream(OutputStream os);
    GeneratedMessageLite getMessage();
}
