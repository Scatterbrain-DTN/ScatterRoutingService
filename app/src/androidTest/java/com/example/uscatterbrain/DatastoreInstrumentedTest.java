package com.example.uscatterbrain;

import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.file.FileStore;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.example.uscatterbrain.network.ScatterDataPacket;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.Sign;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThat;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DatastoreInstrumentedTest {

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
        sm.setTo(new byte[16]);
        return sm;
    }

    public List<ScatterMessage> defaultMessages(int count) {
        List<ScatterMessage> sl = new ArrayList<>();
        for(int i=0;i<count;i++) {
            sl.add(defaultMessage());
        }
        return sl;
    }

    static volatile boolean testRunning = false;


    @Test
    public void publicApiInsertsMessage() throws ExecutionException, InterruptedException {
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());
        datastore.clear();
        List<ScatterMessage> sms = defaultMessages(1);

        try {
            testRunning = true;
            FutureTask<List<Long>> result = datastore.insertMessages(sms);
            List<Long> longs = result.get();
            assertThat(longs.size(), is(1));
            testRunning = false;


            blockForThread();

        }
        catch(ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void publicApiQueryMessageByIdentity() throws InterruptedException, ExecutionException {
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());
        datastore.clear();
        ScatterMessage sm = defaultMessage();
        try {
            Future<Long> result = datastore.insertMessage(sm);
            Long rowids = result.get();
            assertThat(rowids, not(0L));
        } catch (ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void topRandomMessagesWork() throws TimeoutException, InterruptedException, ExecutionException {
        ScatterRoutingService service = getService();
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());
        datastore.clear();
        List<ScatterMessage> messages = defaultMessages(20);
        try {
            testRunning = true;
            FutureTask<List<Long>> result = datastore.insertMessages(messages);
            List<Long> rowids = result.get();
            assertThat(rowids.size(), is(20));
            new Handler(Looper.getMainLooper()).post(() -> {
                datastore.getTopRandomMessages(5).observe(service, messages1 -> {
                    assertThat(messages1.size(), is(5));
                    System.out.println("done");
                    testRunning = false;
                });
            });
            blockForThread();
        } catch(ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void getAllFilesWorks() throws TimeoutException, ExecutionException, InterruptedException {
        ScatterRoutingService service = getService();
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());
        datastore.clear();
        List<ScatterMessage> messages = defaultMessages(30);
        try {
            testRunning = true;
            FutureTask<List<Long>>  result = datastore.insertMessages(messages);
            assertThat(result.get().size(), is(30));
            new Handler(Looper.getMainLooper()).post(() -> {
                datastore.getAllFiles().observe(service, diskFiles -> {
                    testRunning = false;
                    assertThat(diskFiles.size(), is(30));
                });
            });
            blockForThread();
        } catch (ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void fileStoreAddWorks() throws TimeoutException, InterruptedException , ExecutionException {
        ScatterRoutingService service = getService();
        FileStore store = FileStore.getFileStore();
        byte[] data = new byte[100];
        Random r = new Random();
        r.nextBytes(data);
        ByteArrayInputStream is = new ByteArrayInputStream(data);

        File f = new File(service.getFilesDir(), "fmef");

        Future<FileStore.FileCallbackResult> deleteResult = store.deleteFile(f.toPath().toAbsolutePath());
        deleteResult.get();
        Future<FileStore.FileCallbackResult> result = store.insertFile(is, f.toPath().toAbsolutePath());
        assertThat(result.get(), is(FileStore.FileCallbackResult.ERR_SUCCESS));

        File verify = new File(service.getFilesDir(), "fmef");
        System.out.println("verify full path " + verify.getAbsolutePath());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Future<FileStore.FileCallbackResult> readresult = store.getFile(bos, verify.toPath().toAbsolutePath());
            assertThat(readresult.get(), is(FileStore.FileCallbackResult.ERR_SUCCESS));
        for (byte datum : data) {
            System.out.print(datum);
        }
            System.out.println();
            for (int i=0;i<bos.toByteArray().length;i++) {
                System.out.print(bos.toByteArray()[i]);
            }
            System.out.println();
            assertArrayEquals(data, bos.toByteArray());
    }

    @Test
    public void hashingFromDiskWorks() throws TimeoutException, InterruptedException, ExecutionException {
        ScatterRoutingService service = getService();
        FileStore store = FileStore.getFileStore();
        byte[] data = new byte[4096*10];
        Random r = new Random();
        r.nextBytes(data);

        ByteArrayInputStream is = new ByteArrayInputStream(data);

        File f = new File(service.getFilesDir(), "fmef");

        Future<FileStore.FileCallbackResult> deleteResult = store.deleteFile(f.toPath().toAbsolutePath());
        deleteResult.get();
        Future<FileStore.FileCallbackResult> result = store.insertFile(is, f.toPath().toAbsolutePath());
        assertThat(result.get(), is(FileStore.FileCallbackResult.ERR_SUCCESS));

        Future<List<ByteString>> hashlist = store.hashFile(f.toPath().toAbsolutePath(), 4096);
        assertThat(hashlist.get() == null, is(false));
        assertThat(hashlist.get().size(), is(10));
    }

    @Test
    public void scatterDataPacketInsertWorks() throws TimeoutException, InterruptedException, ExecutionException, NullPointerException {
        ScatterRoutingService service = getService();
        FileStore store = FileStore.getFileStore();
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());

        byte[] data = new byte[4096*10];
        Random r = new Random();
        r.nextBytes(data);

        ByteArrayInputStream is = new ByteArrayInputStream(data);

        File f = new File(service.getFilesDir(), "fmef");

        Future<FileStore.FileCallbackResult> deleteResult = store.deleteFile(f.toPath().toAbsolutePath());
        deleteResult.get();
        Future<FileStore.FileCallbackResult> result = store.insertFile(is, f.toPath().toAbsolutePath());
        assertThat(result.get(), is(FileStore.FileCallbackResult.ERR_SUCCESS));

        ScatterDataPacket bd = new ScatterDataPacket.Builder()
                .setBlockSize(1024)
                .setFragmentFile(f)
                .setFromAddress(ByteString.copyFrom(new byte[32]))
                .setToAddress(ByteString.copyFrom(new byte[32]))
                .setSessionID(0)
                .setApplication("test")
                .build();

        byte[] pubkey = new byte[Sign.PUBLICKEYBYTES];
        byte[] secretkey = new byte[Sign.SECRETKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(pubkey,secretkey);
        bd.getHeader().signEd25519(secretkey);

        FutureTask<ScatterbrainDatastore.ScatterDataPacketInsertResult<Long>> res = datastore.insertDataPacket(bd);
        assertThat(res.get().getSuccessCode(), is(ScatterbrainDatastore.DatastoreSuccessCode.DATASTORE_SUCCESS_CODE_SUCCESS));

        List<Long> ids = new ArrayList<>();
        ids.add(res.get().getScatterMessageId());

        Log.e("debug", "getting packet");
        FutureTask<List<ScatterDataPacket>> newdplist = datastore.getDataPacket(ids);
        Log.e("debug", "got packet");
        assertThat(newdplist.get().size(), is(1));
        assertThat(newdplist.get().get(0).getHeader().getHashList().size(), is(bd.getHeader().getHashList().size()));

        assertThat(newdplist.get().get(0).getHeader().verifyed25519(pubkey), is(true));
    }


    @Test
    public void testBase64() throws InterruptedException, ExecutionException {
        byte[] privkey = new byte[Sign.ED25519_SECRETKEYBYTES];
        byte[] mScatterbrainPubKey = new byte[Sign.ED25519_PUBLICKEYBYTES];
        LibsodiumInterface.getSodium().crypto_sign_keypair(mScatterbrainPubKey, privkey);
        Log.e("base64", LibsodiumInterface.base64enc(mScatterbrainPubKey));
        assertArrayEquals(LibsodiumInterface.base64dec(LibsodiumInterface.base64enc(mScatterbrainPubKey)),
                mScatterbrainPubKey);
    }

    @Test
    public void datastoreIdentityWorks() throws TimeoutException, ExecutionException, InterruptedException {
        ScatterRoutingService service = getService();
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(service);
        datastore.clear();
        com.example.uscatterbrain.identity.Identity id = com.example.uscatterbrain.identity.Identity.newBuilder(service)
                .setName("Menhera Chan")
                .generateKeypair()
                .build();

        List<com.example.uscatterbrain.identity.Identity> identityList = new ArrayList<>();
        identityList.add(id);

        FutureTask<ScatterbrainDatastore.ScatterDataPacketInsertResult<List<Long>>> res =
                datastore.insertIdentity(identityList);

        assertThat(res.get().getSuccessCode(), is(ScatterbrainDatastore.DatastoreSuccessCode.DATASTORE_SUCCESS_CODE_SUCCESS));
        assertThat(res.get().getScatterMessageId().size(), is(1));

        FutureTask<List<com.example.uscatterbrain.identity.Identity>> newid =
        datastore.getIdentity(res.get().getScatterMessageId());

        assertThat(newid.get().size(), is(1));
        assertThat(newid.get().get(0).size(), is(1));
        assertArrayEquals(id.sumBytes().toByteArray(), newid.get().get(0).sumBytes().toByteArray());
        assertArrayEquals(newid.get().get(0).getSig(), id.getSig());
        assertThat(newid.get().get(0).verifyed25519(id.getPubkey()), is(true));


    }
}
