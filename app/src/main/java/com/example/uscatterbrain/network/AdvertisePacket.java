package com.example.uscatterbrain.network;

import com.example.uscatterbrain.API.HighLevelAPI;
import com.example.uscatterbrain.DeviceProfile;
import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class AdvertisePacket {
    private final int magic = 5768;
    private ScatterProto.Advertise advertise;

    public AdvertisePacket(DeviceProfile profile) {
        ScatterProto.Advertise.Builder proto;
        proto = ScatterProto.Advertise.newBuilder();
        proto.setMagic(magic);
        proto.setProtocolversion(HighLevelAPI.PROTOVERSION);
        advertise = proto.build();
    }

    public AdvertisePacket(byte[] bytes) throws InvalidProtocolBufferException {
        advertise = ScatterProto.Advertise.parseFrom(bytes);
    }

    public AdvertisePacket(InputStream is) throws IOException {
        advertise = ScatterProto.Advertise.parseFrom(is);
    }

    public void writeToOutputStream(OutputStream out) throws IOException {
        advertise.writeTo(out);
    }

    public byte[] getBytes() {
        return advertise.toByteArray();
    }

    public ScatterProto.Advertise getAdvertise() {
        return advertise;
    }
}
