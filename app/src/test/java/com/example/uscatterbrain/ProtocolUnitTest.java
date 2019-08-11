package com.example.uscatterbrain;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.TempDirectory;

import java.util.Random;


@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ProtocolUnitTest {

    private Random localRandom;
    private TempDirectory td;
    private final String TMPDIR = "tmpdir3";

    @Before
    public void setup() {
        localRandom = new Random(System.currentTimeMillis());
        td = new TempDirectory(TMPDIR);
    }


    @After
    public void cleanup() {
        td.destroy();
    }

}
