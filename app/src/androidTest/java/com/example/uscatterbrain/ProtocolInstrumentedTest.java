package com.example.uscatterbrain;

import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockDataPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;
import com.goterl.lazycode.lazysodium.interfaces.Sign;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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

    public BlockDataPacket getPacket() {
        List<ByteString> bl = new ArrayList<ByteString>();
        bl.add(ByteString.EMPTY);
        BlockDataPacket bd = new BlockDataPacket.Builder()
                .setApplication("test".getBytes())
                .setSessionID(0)
                .setHashes(bl)
                .setFromFingerprint(ByteString.copyFrom(new byte[1]))
                .setToFingerprint(ByteString.copyFrom(new byte[1]))
                .setToDisk(false)
                .build();
        return bd;
    }

    public BlockDataPacket getPacket(InputStream is) {
        List<ByteString> bl = new ArrayList<ByteString>();
        bl.add(ByteString.EMPTY);
        BlockDataPacket bd = new BlockDataPacket.Builder()
                .setApplication("test".getBytes())
                .setSessionID(0)
                .setHashes(bl)
                .setFromFingerprint(ByteString.copyFrom(new byte[1]))
                .setToFingerprint(ByteString.copyFrom(new byte[1]))
                .setToDisk(false)
                .setFragmentStream(is)
                .build();

        return bd;
    }

    public BlockSequencePacket getSequencePacket(int seq) {
        BlockSequencePacket bs = new BlockSequencePacket.Builder()
                .setData(ByteString.copyFrom(new byte[20]))
                .setSequenceNumber(seq)
                .build();
        return bs;
    }

    public BlockDataPacket getSignedPacket() {
        BlockDataPacket bd = getPacket();

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
        BlockDataPacket bd = getPacket();

        byte[] bytelist = bd.getBytes();

        Log.e("debug", "" +bytelist.length);

        try {
            BlockDataPacket newbd = new BlockDataPacket(bytelist);
            assertThat(newbd.getApplication(), is("test".getBytes()));
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void blockDataPacketSignatureWorks() throws TimeoutException {
        BlockDataPacket bd = getPacket();

        byte[] privkey = new byte[Sign.SECRETKEYBYTES];
        byte[] pubkey = new byte[Sign.PUBLICKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(pubkey, privkey);

        assertThat(bd.signEd25519(privkey), is(true));

        assertThat(bd.getSig().toByteArray() != null, is(true));

        byte[] bytelist = bd.getBytes();

        try {
            BlockDataPacket newbd = new BlockDataPacket(bytelist);
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

        try {
            BlockSequencePacket newbs = new BlockSequencePacket(data);
            assertArrayEquals(newbs.calculateHash(), firsthash);
        }
        catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void blockSequencePacketWorks() throws TimeoutException {
        BlockSequencePacket bs = getSequencePacket(0);

        byte[] data = bs.getBytes();

        try {
            BlockSequencePacket newbs = new BlockSequencePacket(data);
            assertThat(newbs.getmData() != null, is(true));
        }
        catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            Assert.fail();
        }
    }


    @Test
    public void multipleBlockSequencePacketsWork() throws TimeoutException {
        byte[] data = new byte[5096];
        ByteArrayInputStream is = new ByteArrayInputStream(data);

        BlockDataPacket bd = getPacket(is);
        int i = 0;
        for (BlockSequencePacket bs : bd) {
            assertThat(bs.getmSequenceNumber(), is(i));
            i++;
        }
    }

}
