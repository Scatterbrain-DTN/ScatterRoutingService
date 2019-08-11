package com.example.uscatterbrain;

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

        try {
            BlockDataPacket bd = new BlockDataPacket(APPNAME, uid1, uid2, false);
            bd.setData(bytes);
            byte[] res = bd.getBytes();
            BlockDataPacket bd2 = new BlockDataPacket(res);
        } catch (InvalidProtocolBufferException p) {
            Assert.fail();
        }
    }

}
