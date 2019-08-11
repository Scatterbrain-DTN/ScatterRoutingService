package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BlockDataPacket {
    private ScatterProto.BlockData.Builder proto;
    private ScatterProto.BlockData blockdata;
    private boolean dataready;
    public BlockDataPacket(String application, UUID to, UUID from, boolean disk) {
        dataready = false;
        proto = ScatterProto.BlockData.newBuilder();
        proto.setApplication(application);
        proto.setFromLow(from.getLeastSignificantBits());
        proto.setFromHigh(from.getMostSignificantBits());
        proto.setToLow(from.getLeastSignificantBits());
        proto.setToHigh(to.getMostSignificantBits());
        proto.setTodisk(disk);
    }

    public BlockDataPacket(byte[] data) throws InvalidProtocolBufferException {
        blockdata = ScatterProto.BlockData.parseFrom(data);
    }

    public BlockDataPacket(InputStream in) throws IOException {
        blockdata = ScatterProto.BlockData.parseFrom(in);
    }

    public void setData(InputStream istream) throws IOException {
        proto.setData(ByteString.readFrom(istream));
        blockdata = proto.build();
        dataready = true;
    }

    public void setData(byte[] bdata) {
        proto.setData(ByteString.copyFrom(bdata));
        blockdata = proto.build();
        dataready = true;
    }


    public void writeToOutputStream(OutputStream out) throws IOException {
        if(dataready)
            blockdata.writeTo(out);
    }

    public byte[] getBytes() {
        if(dataready)
            return blockdata.toByteArray();
        else
            return null;
    }

    public ScatterProto.BlockData getBlockdata() {
        return blockdata;
    }
}
