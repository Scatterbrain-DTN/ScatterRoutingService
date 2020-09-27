package com.example.uscatterbrain;

import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;

import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.ScatterbrainDatastoreImpl;
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

import io.reactivex.schedulers.TestScheduler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
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

    public ScatterRoutingServiceImpl getService() throws TimeoutException {
        Intent bindIntent = new Intent(ApplicationProvider.getApplicationContext(), ScatterRoutingServiceImpl.class);
        IBinder binder = serviceRule.bindService(bindIntent);
        return ((ScatterRoutingServiceImpl.ScatterBinder)binder).getService();
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
        ScatterbrainDatastoreImpl datastore = new ScatterbrainDatastoreImpl(
                ApplicationProvider.getApplicationContext(),
                Room.databaseBuilder(ApplicationProvider.getApplicationContext(), Datastore.class, ScatterbrainDatastore.DATABASE_NAME).build(),
                new TestScheduler()
        );
        datastore.clear();
        List<ScatterMessage> sms = defaultMessages(1);

        try {
            assertNull(datastore.insertMessages(sms).blockingGet());
        }
        catch(ScatterbrainDatastoreImpl.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void publicApiQueryMessageByIdentity() throws InterruptedException, ExecutionException {
        ScatterbrainDatastoreImpl datastore = new ScatterbrainDatastoreImpl(
                ApplicationProvider.getApplicationContext(),
                Room.databaseBuilder(ApplicationProvider.getApplicationContext(), Datastore.class, ScatterbrainDatastore.DATABASE_NAME).build(),
                new TestScheduler()
        );
        datastore.clear();
        ScatterMessage sm = defaultMessage();
        try {
            assertNull(datastore.insertMessage(sm).blockingGet());
        } catch (ScatterbrainDatastoreImpl.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void topRandomMessagesWork() throws TimeoutException, InterruptedException, ExecutionException {
        ScatterRoutingServiceImpl service = getService();
        ScatterbrainDatastoreImpl datastore = new ScatterbrainDatastoreImpl(
                ApplicationProvider.getApplicationContext(),
                Room.databaseBuilder(ApplicationProvider.getApplicationContext(), Datastore.class, ScatterbrainDatastore.DATABASE_NAME).build(),
                new TestScheduler()
        );
        datastore.clear();
        List<ScatterMessage> messages = defaultMessages(20);
        try {
            assertNull(datastore.insertMessages(messages).blockingGet());
        } catch(ScatterbrainDatastoreImpl.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void getAllFilesWorks() throws TimeoutException, ExecutionException, InterruptedException {
        ScatterRoutingServiceImpl service = getService();
        ScatterbrainDatastoreImpl datastore = new ScatterbrainDatastoreImpl(
                ApplicationProvider.getApplicationContext(),
                Room.databaseBuilder(ApplicationProvider.getApplicationContext(), Datastore.class, ScatterbrainDatastore.DATABASE_NAME).build(),
                new TestScheduler()
        );
        datastore.clear();
        List<ScatterMessage> messages = defaultMessages(30);
        try {
            assertNull(datastore.insertMessages(messages).blockingGet());
        } catch (ScatterbrainDatastoreImpl.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void fileStoreAddWorks() throws TimeoutException, InterruptedException , ExecutionException {
        ScatterRoutingServiceImpl service = getService();
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
        ScatterRoutingServiceImpl service = getService();
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
        ScatterRoutingServiceImpl service = getService();
        FileStore store = FileStore.getFileStore();
        ScatterbrainDatastoreImpl datastore = new ScatterbrainDatastoreImpl(
                ApplicationProvider.getApplicationContext(),
                Room.databaseBuilder(ApplicationProvider.getApplicationContext(), Datastore.class, ScatterbrainDatastore.DATABASE_NAME).build(),
                new TestScheduler()
        );

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

        assertNull(datastore.insertDataPacket(bd).blockingGet());
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
        ScatterRoutingServiceImpl service = getService();
        ScatterbrainDatastoreImpl datastore = new ScatterbrainDatastoreImpl(
                ApplicationProvider.getApplicationContext(),
                Room.databaseBuilder(ApplicationProvider.getApplicationContext(), Datastore.class, ScatterbrainDatastore.DATABASE_NAME).build(),
                new TestScheduler()
        );
        datastore.clear();
        com.example.uscatterbrain.identity.Identity id = com.example.uscatterbrain.identity.Identity.newBuilder(service)
                .setName("Menhera Chan")
                .generateKeypair()
                .build();

        List<com.example.uscatterbrain.identity.Identity> identityList = new ArrayList<>();
        identityList.add(id);

        assertNull(datastore.insertIdentity(identityList).blockingGet());
    }
}
