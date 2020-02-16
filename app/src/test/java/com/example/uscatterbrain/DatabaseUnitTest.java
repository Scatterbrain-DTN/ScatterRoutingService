package com.example.uscatterbrain;

import android.app.Service;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.Observer;
import androidx.room.Room;

import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.entities.ScatterMessagesWithFiles;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class DatabaseUnitTest {

    public Datastore buildDB() {
        Datastore db = Room.databaseBuilder(
                RuntimeEnvironment.application,
                Datastore.class,
                "testdatabase").allowMainThreadQueries().build();

        return db;
    }

    public void blockForThread() {
        while(testRunning) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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

    static volatile boolean testRunning = false;


    @Test
    public void dataStoreInitializesSuccessfully() {
        Datastore db = buildDB();
        db.close();
    }

    @Test
    public void publicApiInsertsMessage() {
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(RuntimeEnvironment.application);
        List<ScatterMessage> sms = defaultMessages(1);

        try {
            testRunning = true;
            datastore.insertMessage(sms, new ScatterbrainDatastore.DatastoreInsertUpdateCallback<List<Long>>() {
                @Override
                public void onRowUpdate(List<Long> rowids) {
                    assertThat(rowids.size(), is(1));
                    assertThat(rowids.get(0), is(1L));
                    testRunning = false;
                }
            });

            blockForThread();

        }
        catch(ScatterbrainDatastore.DatastoreInsertException e) {
            fail();
        }
    }

    @Test
    public void publicApiQueryMessageByIdentity() {
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(RuntimeEnvironment.application);
        ScatterMessage sm = defaultMessage();
        try {
            testRunning = true;
            datastore.insertMessage(sm, new ScatterbrainDatastore.DatastoreInsertUpdateCallback<Long>() {
                @Override
                public void onRowUpdate(Long rowids) {
                    assertThat(rowids, is(1L));
                    testRunning = false;

                }
            });

            blockForThread();
        } catch (ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    public void topRandomMessagesWork() {
        ServiceController<ScatterRoutingService> service = Robolectric.buildService(ScatterRoutingService.class);
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(RuntimeEnvironment.application);
        List<ScatterMessage> messages = defaultMessages(20);
        service.bind();
        try {
            testRunning = true;
            datastore.insertMessage(messages, new ScatterbrainDatastore.DatastoreInsertUpdateCallback<List<Long>>() {
                @Override
                public void onRowUpdate(List<Long> rowids) {
                    assertThat(rowids.size(), is(20));
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            datastore.getTopRandomMessages(5).observe(service.get(), new Observer<List<ScatterMessage>>() {
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
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            blockForThread();
        } catch(ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

}
