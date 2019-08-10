package com.example.uscatterbrain.network;

import com.example.uscatterbrain.API.HighLevelAPI;
import com.example.uscatterbrain.DeviceProfile;
import com.example.uscatterbrain.ScatterProto;
import java.io.IOException;
import java.io.OutputStream;

public class AdvertisePacket {
    private final int magic = 5768;
    private ScatterProto.Advertise advertise;

    public AdvertisePacket(DeviceProfile profile) {
        ScatterProto.Advertise.Builder proto;
        proto = ScatterProto.Advertise.newBuilder();
        proto.setMagic(magic);
        proto.setProtocolversion(HighLevelAPI.PROTOVERSION);
        proto.setUuidUpper(profile.getLUID().getMostSignificantBits());
        proto.setUuidLower(profile.getLUID().getLeastSignificantBits());
        advertise = proto.build();
    }

    public void writeToOutputStream(OutputStream out) throws IOException {
        advertise.writeTo(out);
    }

    public byte[] getBytes() {
        return advertise.toByteArray();
    }
}
