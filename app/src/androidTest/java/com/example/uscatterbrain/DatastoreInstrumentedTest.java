package com.example.uscatterbrain;

import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.file.FileStore;
import com.google.protobuf.ByteString;

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
        sm.addFile(new DiskFiles());
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
    public void publicApiInsertsMessage() {
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());
        datastore.clear();
        List<ScatterMessage> sms = defaultMessages(1);

        try {
            testRunning = true;
            datastore.insertMessage(sms, rowids -> {
                assertThat(rowids.size(), is(1));
                testRunning = false;
            });

            blockForThread();

        }
        catch(ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void publicApiQueryMessageByIdentity() {
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());
        datastore.clear();
        ScatterMessage sm = defaultMessage();
        try {
            testRunning = true;
            datastore.insertMessage(sm, rowids -> {
                assertThat(rowids, not(0L));
                testRunning = false;

            });

            blockForThread();
        } catch (ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void topRandomMessagesWork() throws TimeoutException {
        ScatterRoutingService service = getService();
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());
        datastore.clear();
        List<ScatterMessage> messages = defaultMessages(20);
        try {
            testRunning = true;
            datastore.insertMessage(messages, rowids -> {
                assertThat(rowids.size(), is(20));
                new Handler(Looper.getMainLooper()).post(() -> datastore.getTopRandomMessages(5).observe(service, messages1 -> {
                    assertThat(messages1.size(), is(5));
                    testRunning = false;
                    System.out.println("done");
                }));
            });
            blockForThread();
        } catch(ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void getAllFilesWorks() throws TimeoutException {
        ScatterRoutingService service = getService();
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(ApplicationProvider.getApplicationContext());
        datastore.clear();
        List<ScatterMessage> messages = defaultMessages(30);
        try {
            testRunning = true;
            datastore.insertMessage(messages, rowids -> new Handler(Looper.getMainLooper()).post(() -> datastore.getAllFiles().observe(service, diskFiles -> {
                testRunning = false;
                assertThat(diskFiles.size(), is(30));
            })));
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
}
