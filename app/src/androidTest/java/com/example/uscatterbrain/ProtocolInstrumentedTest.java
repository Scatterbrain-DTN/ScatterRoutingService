package com.example.uscatterbrain;

import android.content.Intent;
import android.os.IBinder;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;

import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.file.FileStore;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.IdentityPacket;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.example.uscatterbrain.network.ScatterDataPacket;
import com.example.uscatterbrain.network.ScatterSerializable;
import com.example.uscatterbrain.network.UpgradePacket;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;
import com.goterl.lazycode.lazysodium.interfaces.Sign;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

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
        return ((ScatterRoutingService.ScatterBinder)binder).getService();
    }

    public ScatterMessage defaultMessage() {
        ScatterMessage sm = new ScatterMessage(new Identity(), new byte[5]);
        sm.setFilePath("/dev/null");
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
        List<ByteString> bl = new ArrayList<>();
        bl.add(ByteString.EMPTY);
        bl.add(ByteString.EMPTY);
        bl.add(ByteString.EMPTY);
        bl.add(ByteString.EMPTY);
        return new BlockHeaderPacket.Builder()
                .setApplication("test".getBytes())
                .setSessionID(0)
                .setHashes(bl)
                .setFromFingerprint(ByteString.copyFrom(new byte[1]))
                .setToFingerprint(ByteString.copyFrom(new byte[1]))
                .setToDisk(false)
                .setBlockSize(1024)
                .build();
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

    public AdvertisePacket getAdvertise() {
        List<ScatterProto.Advertise.Provides> p = new ArrayList<>();
        p.add(ScatterProto.Advertise.Provides.BLE);
        return new AdvertisePacket.Builder()
                .setProvides(p)
                .build();
    }

    public UpgradePacket getUpgrade() {
        return new UpgradePacket.Builder()
                .setProvides(ScatterProto.Advertise.Provides.ULTRASOUND)
                .setSessionID(1)
                .build();
    }

    public BlockSequencePacket getSequencePacket(int seq) {
        return new BlockSequencePacket.Builder()
                .setData(ByteString.copyFrom(new byte[20]))
                .setSequenceNumber(seq)
                .build();
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
    public void testSodium() {
        assertThat(LibsodiumInterface.getSignNative() == null, is(false));

        byte[] pubkey = new byte[Sign.PUBLICKEYBYTES];
        byte[] privkey = new byte[Sign.SECRETKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(pubkey, privkey);

        byte[] fingerprint = new byte[GenericHash.BYTES];
        LibsodiumInterface.getSodium().crypto_generichash(fingerprint, fingerprint.length,
                pubkey, pubkey.length, null, 0);
    }


    @Test
    public void blockDataPacketFromByteArray() {
        BlockHeaderPacket bd = getHeaderPacket();

        byte[] bytelist = bd.getBytes();

        try {
            ByteArrayInputStream is = new ByteArrayInputStream(bytelist);
            BlockHeaderPacket newbd = BlockHeaderPacket.parseFrom(is);
            assertThat(Objects.requireNonNull(newbd).getApplication(), is("test".getBytes()));
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void blockDataPacketSignatureWorks() {
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
            assertThat(Objects.requireNonNull(newbd).getApplication(), is("test".getBytes()));
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
    public void hashListWorks() {
        BlockHeaderPacket bd = getHeaderPacket();
        int size = bd.getHashList().size();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        bd.writeToStream(os);

        try {
            ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
            BlockHeaderPacket blockHeaderPacket = BlockHeaderPacket.parseFrom(is);
            assertThat(blockHeaderPacket != null, is(true));
            assertThat(Objects.requireNonNull(blockHeaderPacket).getHashList().size(), is(size));
        } catch (Exception e) {
            Assert.fail();
        }
    }


    @Test
    public void blockSequencePacketCalculateHashWorks() {
        BlockSequencePacket bs = getSequencePacket(0);
        byte[] firsthash = bs.calculateHash();

        byte[] data = bs.getBytes();

        ByteArrayInputStream is = new ByteArrayInputStream(data);
        BlockSequencePacket newbs = BlockSequencePacket.parseFrom(is);
        assertArrayEquals(Objects.requireNonNull(newbs).calculateHash(), firsthash);
    }

    @Test
    public void blockSequencePacketWorks() {
        BlockSequencePacket bs = getSequencePacket(0);

        byte[] data = bs.getBytes();


        ByteArrayInputStream is = new ByteArrayInputStream(data);
        BlockSequencePacket newbs = BlockSequencePacket.parseFrom(is);
        assertThat(Objects.requireNonNull(newbs).getmData() != null, is(true));
    }


    @Test
    public void multipleBlockSequencePacketsVerify() throws TimeoutException, ExecutionException, InterruptedException {
        byte[] data = new byte[4096*20];
        Random r = new Random();
        r.nextBytes(data);
        ScatterRoutingService service = getService();
        FileStore store = FileStore.getFileStore();
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        List<ByteString> bl = new ArrayList<>();
        bl.add(ByteString.EMPTY);


        File file = new File(service.getFilesDir(), "test2");
        Future<FileStore.FileCallbackResult> resultFuture = store.deleteFile(file.toPath().toAbsolutePath());
        resultFuture.get();
        resultFuture =  store.insertFile(is, file.toPath().toAbsolutePath());
        assertThat(resultFuture.get(), is(FileStore.FileCallbackResult.ERR_SUCCESS));
        ScatterDataPacket bd = new ScatterDataPacket.Builder()
                    .setBlockSize(1024)
                    .setFragmentFile(file)
                    .setFromAddress(ByteString.copyFrom(new byte[32]))
                    .setToAddress(ByteString.copyFrom(new byte[32]))
                    .setSessionID(0)
                    .setApplication("test")
                    .build();

        assertThat(bd != null, is(true));

        ByteArrayOutputStream os = new ByteArrayOutputStream();

        int i = 0;
        try {
            for (ScatterSerializable bs : Objects.requireNonNull(bd)) {
                if (i == 0) {
                    assertThat(((BlockHeaderPacket)bs).getHashList().size(), is(80));
                }
                bs.writeToStream(os);
                i++;
            }

            assertThat(i, is(81));

            ByteArrayInputStream bis = new ByteArrayInputStream(os.toByteArray());
            File newfile = new File(service.getFilesDir(), "newfile");
            FileStore.getFileStore().deleteFile(newfile.toPath().toAbsolutePath()).get();
            ScatterDataPacket newdp = ScatterDataPacket.parseFrom(bis, newfile);
            assertThat(newdp != null, is(true));
            assertThat(Objects.requireNonNull(newdp).isHashValid(), is(true));
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void AdvertisePacketWorks() {
        AdvertisePacket ad = getAdvertise();

        byte[] data = ad.getBytes();
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        AdvertisePacket n = AdvertisePacket.parseFrom(is);
        assertThat(Objects.requireNonNull(n).getProvides().get(0), is(ScatterProto.Advertise.Provides.BLE));
    }

    @Test
    public void UpgradePacketWorks() {
        UpgradePacket up = getUpgrade();

        byte[] data = up.getBytes();
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        UpgradePacket nu = UpgradePacket.parseFrom(is);
        assertThat(Objects.requireNonNull(nu).getProvides(), is(ScatterProto.Advertise.Provides.ULTRASOUND));
    }

    @Test
    public void IdentityPacketWorks() {
        byte[] signingkey = new byte[Sign.SECRETKEYBYTES];
        byte[] pubkey = new byte[Sign.PUBLICKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(pubkey, signingkey);
        IdentityPacket id = getIdentity(signingkey);

        byte[] data = id.getBytes();

        ByteArrayInputStream is = new ByteArrayInputStream(data);
        IdentityPacket idnew = IdentityPacket.parseFrom(is);
        assertThat(Objects.requireNonNull(idnew).getKeys().get("scatterbrain"), is(ByteString.copyFromUtf8("fmeef")));
        assertThat(idnew.verifyed25519(pubkey), is(true));
    }

    @Test
    public void IdentityWrapperWorks() throws TimeoutException {
        ScatterRoutingService service = getService();
        com.example.uscatterbrain.identity.Identity id = com.example.uscatterbrain.identity.Identity.newBuilder(service)
                .setName("Menhera Chan")
                .generateKeypair()
                .build();

        byte[] privkey = id.getPrivKey();
        assertThat(privkey != null, is(true));

        ByteString bs = id.getByteString();

        ByteArrayInputStream is = new ByteArrayInputStream(bs.toByteArray());

        try {
            com.example.uscatterbrain.identity.Identity newid = com.example.uscatterbrain.identity.Identity.parseFrom(is, service);
            assertThat(newid != null, is(true));
            assertThat(newid.getPrivKey() != null, is(true));
            assertArrayEquals(newid.getPrivKey(), privkey);
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

}
