package com.example.uscatterbrain;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BlockDataPacket;
import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.TempDirectory;

import java.io.IOException;
import java.util.Random;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;


@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ProtocolUnitTest {

    private Random localRandom;
    private TempDirectory td;
    private final String TMPDIR = "tmpdir3";
    private final String APPNAME = "ScatterBean";

    @Before
    public void setup() {
        localRandom = new Random(System.currentTimeMillis());
        td = new TempDirectory(TMPDIR);
    }

    @After
    public void cleanup() {
        td.destroy();
    }


    @Test
    public void blockDataSerializesByteArray() {
        byte[] bytes = new byte[1024];
        localRandom.nextBytes(bytes);
        UUID uid1 = UUID.randomUUID();
        UUID uid2 = UUID.randomUUID();
        byte[] res = null;

        try {
            BlockDataPacket bd = new BlockDataPacket(APPNAME, uid1, uid2, false);
            bd.setData(bytes);
            res = bd.getBytes();
            BlockDataPacket bd2 = new BlockDataPacket(res);
            assertArrayEquals(bd2.getBlockdata().getData().toByteArray(), bytes);

        } catch (InvalidProtocolBufferException p) {
            Assert.fail("protobuf invalid");
        }

    }

    @Test
    public void advertiseSerializeByteArray() {
        UUID uid = UUID.randomUUID();
        AdvertisePacket ad2 = null;
        DeviceProfile dp = new DeviceProfile(DeviceProfile.HardwareServices.BLUETOOTHCLASSIC,uid);
        try {
            AdvertisePacket ad = new AdvertisePacket(dp);
            byte[] b = ad.getBytes();
            ad2 = new AdvertisePacket(b);
        } catch(InvalidProtocolBufferException i) {
            Assert.fail("protobuf invalid");
        }

        assertEquals("UUID lower is preserved", ad2.getAdvertise().getUuidLower(), uid.getLeastSignificantBits());
        assertEquals("UUID upper is preserved", ad2.getAdvertise().getUuidUpper(), uid.getMostSignificantBits());
    }

}
