package com.example.uscatterbrain;

import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;

import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.IdentityPacket;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.example.uscatterbrain.network.ScatterDataPacket;
import com.example.uscatterbrain.network.ScatterSerializable;
import com.example.uscatterbrain.network.UpgradePacket;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;
import com.goterl.lazycode.lazysodium.interfaces.Sign;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ProtocolInstrumentedTest {

    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();


    public void blockForThread() {
        while(testRunning) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public ScatterRoutingService getService() throws TimeoutException {
        Intent bindIntent = new Intent(ApplicationProvider.getApplicationContext(), ScatterRoutingService.class);
        IBinder binder = serviceRule.bindService(bindIntent);
        ScatterRoutingService service = ((ScatterRoutingService.ScatterBinder)binder).getService();
        return service;
    }

    public ScatterMessage defaultMessage() {
        ScatterMessage sm = new ScatterMessage(new Identity(), new byte[5]);
        sm.addFile(new DiskFiles());
        return sm;
    }

    public List<ScatterMessage> defaultMessages(int count) {
        List<ScatterMessage> sl = new ArrayList<>();
        for(int i=0;i<count;i++) {
            sl.add(defaultMessage());
        }
        return sl;
    }

    public BlockHeaderPacket getHeaderPacket() {
        List<ByteString> bl = new ArrayList<ByteString>();
        bl.add(ByteString.EMPTY);
        BlockHeaderPacket bd = new BlockHeaderPacket.Builder()
                .setApplication("test".getBytes())
                .setSessionID(0)
                .setHashes(bl)
                .setFromFingerprint(ByteString.copyFrom(new byte[1]))
                .setToFingerprint(ByteString.copyFrom(new byte[1]))
                .setToDisk(false)
                .build();
        return bd;
    }


    public IdentityPacket getIdentity(byte[] privkey) {
        Map<String, ByteString> keymap = new HashMap<>();
        keymap.put("scatterbrain", ByteString.copyFromUtf8("fmeef"));
        return new IdentityPacket.Builder()
                .setName("name")
                .setKeys(keymap)
                .setSignKey(privkey)
                .build();
    }

    public ScatterDataPacket getPacket(InputStream is, int count, int bs) {
        List<ByteString> bl = new ArrayList<ByteString>();
        bl.add(ByteString.EMPTY);

        ScatterDataPacket bd = new ScatterDataPacket.Builder()
                .setFragmentCount(count)
                .setBlockSize(bs)
                .setFragmentStream(is)
                .setFromAddress(new byte[32])
                .setToAddress(new byte[32])
                .setSessionID(0)
                .setApplication("test")
                .build();


        return bd;
    }

    public AdvertisePacket getAdvertise() {
        List<ScatterProto.Advertise.Provides> p = new ArrayList<>();
        p.add(ScatterProto.Advertise.Provides.BLE);
        AdvertisePacket advertisePacket = new AdvertisePacket.Builder()
                .setProvides(p)
                .build();
        return advertisePacket;
    }

    public UpgradePacket getUpgrade() {
        return new UpgradePacket.Builder()
                .setProvides(ScatterProto.Advertise.Provides.ULTRASOUND)
                .setSessionID(1)
                .build();
    }

    public BlockSequencePacket getSequencePacket(int seq) {
        BlockSequencePacket bs = new BlockSequencePacket.Builder()
                .setData(ByteString.copyFrom(new byte[20]))
                .setSequenceNumber(seq)
                .build();
        return bs;
    }

    public BlockHeaderPacket getSignedPacket() {
        BlockHeaderPacket bd = getHeaderPacket();

        byte[] privkey = new byte[Sign.SECRETKEYBYTES];
        byte[] pubkey = new byte[Sign.PUBLICKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(pubkey, privkey);
        bd.signEd25519(privkey);
        return bd;
    }

    static volatile boolean testRunning = false;

    @Test
    public void testSodium() throws TimeoutException {
        assertThat(LibsodiumInterface.getSignNative() == null, is(false));

        byte[] pubkey = new byte[Sign.PUBLICKEYBYTES];
        byte[] privkey = new byte[Sign.SECRETKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(pubkey, privkey);

        byte[] fingerprint = new byte[GenericHash.BYTES];
        LibsodiumInterface.getSodium().crypto_generichash(fingerprint, fingerprint.length,
                pubkey, pubkey.length, null, 0);
    }


    @Test
    public void blockDataPacketFromByteArray() throws TimeoutException {
        BlockHeaderPacket bd = getHeaderPacket();

        byte[] bytelist = bd.getBytes();

        Log.e("debug", "" +bytelist.length);
        try {
            ByteArrayInputStream is = new ByteArrayInputStream(bytelist);
            BlockHeaderPacket newbd = BlockHeaderPacket.parseFrom(is);
            assertThat(newbd.getApplication(), is("test".getBytes()));
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void blockDataPacketSignatureWorks() throws TimeoutException {
        BlockHeaderPacket bd = getHeaderPacket();

        byte[] privkey = new byte[Sign.SECRETKEYBYTES];
        byte[] pubkey = new byte[Sign.PUBLICKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(pubkey, privkey);

        assertThat(bd.signEd25519(privkey), is(true));

        assertThat(bd.getSig().toByteArray() != null, is(true));

        byte[] bytelist = bd.getBytes();

        try {
            ByteArrayInputStream is = new ByteArrayInputStream(bytelist);
            BlockHeaderPacket newbd = BlockHeaderPacket.parseFrom(is);
            assertThat(newbd.getApplication(), is("test".getBytes()));
            assertThat(newbd.getSig() != null, is(true));
            assertEquals(bd.getSig().size(), newbd.getSig().size());
            assertArrayEquals(bd.getSig().toByteArray(), newbd.getSig().toByteArray());
            assertThat(newbd.verifyed25519(pubkey), is(true));
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }


    @Test
    public void blockSequencePacketCalculateHashWorks() throws TimeoutException {
        BlockSequencePacket bs = getSequencePacket(0);
        byte[] firsthash = bs.calculateHash();

        byte[] data = bs.getBytes();

        ByteArrayInputStream is = new ByteArrayInputStream(data);
        BlockSequencePacket newbs = BlockSequencePacket.parseFrom(is);
        assertArrayEquals(newbs.calculateHash(), firsthash);
    }

    @Test
    public void blockSequencePacketWorks() throws TimeoutException {
        BlockSequencePacket bs = getSequencePacket(0);

        byte[] data = bs.getBytes();


        ByteArrayInputStream is = new ByteArrayInputStream(data);
        BlockSequencePacket newbs = BlockSequencePacket.parseFrom(is);
        assertThat(newbs.getmData() != null, is(true));
    }


    @Test
    public void multipleBlockSequencePacketsSend() throws TimeoutException {
        byte[] data = new byte[4096];
        ByteArrayInputStream is = new ByteArrayInputStream(data);

        ScatterDataPacket bd = getPacket(is, 2, 2048);
        int i = 0;
        for (ScatterSerializable bs : bd) {
            assertThat(bs.getBytes().length > 0, is(true));
            i++;
        }
    }



    @Test
    public void multipleBlockSequencePacketsVerify() throws TimeoutException {
        byte[] data = new byte[4096];
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        ScatterDataPacket bd = getPacket(is, 2, 2048);

        ByteArrayOutputStream os = new ByteArrayOutputStream(6000);

        int i = 0;
        try {
            for (ScatterSerializable bs : bd) {
                os.write(bs.getBytes());
                i++;
            }

        } catch (Exception e) {
            Assert.fail();
        }
    }

    @Test
    public void AdvertisePacketWorks() throws TimeoutException {
        AdvertisePacket ad = getAdvertise();

        byte[] data = ad.getBytes();
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        AdvertisePacket n = AdvertisePacket.parseFrom(is);
        assertThat(n.getProvides().get(0), is(ScatterProto.Advertise.Provides.BLE));
    }

    @Test
    public void UpgradePacketWorks() throws TimeoutException {
        UpgradePacket up = getUpgrade();

        byte[] data = up.getBytes();
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        UpgradePacket nu = UpgradePacket.parseFrom(is);
        assertThat(nu.getProvides(), is(ScatterProto.Advertise.Provides.ULTRASOUND));
    }

    @Test
    public void IdentityPacketWorks() throws TimeoutException {
        byte[] signingkey = new byte[Sign.SECRETKEYBYTES];
        byte[] pubkey = new byte[Sign.PUBLICKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(pubkey, signingkey);
        IdentityPacket id = getIdentity(signingkey);

        byte[] data = id.getBytes();

        ByteArrayInputStream is = new ByteArrayInputStream(data);
        IdentityPacket idnew = IdentityPacket.parseFrom(is);
        assertThat(idnew.getKeys().get("scatterbrain"), is(ByteString.copyFromUtf8("fmeef")));
        assertThat(idnew.verifyed25519(pubkey), is(true));
    }

}
