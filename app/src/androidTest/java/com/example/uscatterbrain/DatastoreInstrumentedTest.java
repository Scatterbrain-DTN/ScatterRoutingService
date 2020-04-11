package com.example.uscatterbrain;

import android.content.Intent;
import android.net.wifi.hotspot2.pps.Credential;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnitRunner;

import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.file.FileStore;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.goterl.lazycode.lazysodium.LazySodiumAndroid;
import com.goterl.lazycode.lazysodium.SodiumAndroid;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;
import com.goterl.lazycode.lazysodium.interfaces.Hash;
import com.goterl.lazycode.lazysodium.interfaces.Sign;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.sql.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        ScatterRoutingService service = ((ScatterRoutingService.ScatterBinder)binder).getService();
        return service;
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
            datastore.insertMessage(sms, new ScatterbrainDatastore.DatastoreInsertUpdateCallback<List<Long>>() {
                @Override
                public void onRowUpdate(List<Long> rowids) {
                    assertThat(rowids.size(), is(1));
                    testRunning = false;
                }
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
            datastore.insertMessage(sm, new ScatterbrainDatastore.DatastoreInsertUpdateCallback<Long>() {
                @Override
                public void onRowUpdate(Long rowids) {
                    assertThat(rowids, not(0L));
                    testRunning = false;

                }
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
            datastore.insertMessage(messages, new ScatterbrainDatastore.DatastoreInsertUpdateCallback<List<Long>>() {
                @Override
                public void onRowUpdate(List<Long> rowids) {
                    assertThat(rowids.size(), is(20));
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            datastore.getTopRandomMessages(5).observe(service, new Observer<List<ScatterMessage>>() {
                                @Override
                                public void onChanged(List<ScatterMessage> messages) {
                                    assertThat(messages.size(), is(5));
                                    testRunning = false;
                                    System.out.println("done");
                                }
                            });
                        }
                    });
                }
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
            datastore.insertMessage(messages, new ScatterbrainDatastore.DatastoreInsertUpdateCallback<List<Long>>() {
                @Override
                public void onRowUpdate(List<Long> rowids) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            datastore.getAllFiles().observe(service, new Observer<List<DiskFiles>>() {
                                @Override
                                public void onChanged(List<DiskFiles> diskFiles) {
                                    testRunning = false;
                                    assertThat(diskFiles.size(), is(30));
                                }
                            });
                        }
                    });
                }
            });
            blockForThread();
        } catch (ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    public void fileStoreAddWorks() throws TimeoutException, InterruptedException , ExecutionException {
        ScatterRoutingService service = getService();
        FileStore store = new FileStore();
        byte[] data = new byte[4096*4];
        ByteArrayInputStream is = new ByteArrayInputStream(data);

        File f = new File(service.getFilesDir(), "fmef");

        Future<FileStore.FileCallbackResult> deleteResult = store.deleteFile(f.toPath());
        deleteResult.get();
        Future<FileStore.FileCallbackResult> result = store.insertFile(is, f.toPath());
        assertThat(result.get(), is(FileStore.FileCallbackResult.ERR_SUCCESS));
    }

    @Test
    public void testBindService() throws TimeoutException {

    }
}
