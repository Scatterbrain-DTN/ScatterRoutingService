package com.example.uscatterbrain;

import android.os.Handler;
import android.os.Looper;

import androidx.room.Room;

import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class DatabaseUnitTest {

    public Datastore buildDB() {

        return Room.databaseBuilder(
                RuntimeEnvironment.application,
                Datastore.class,
                "testdatabase").allowMainThreadQueries().build();
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
            datastore.insertMessage(sms, rowids -> {
                assertThat(rowids.size(), is(1));
                assertThat(rowids.get(0), is(1L));
                testRunning = false;
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
            datastore.insertMessage(sm, rowids -> {
                assertThat(rowids, is(1L));
                testRunning = false;

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
            datastore.insertMessage(messages, rowids -> {
                assertThat(rowids.size(), is(20));
                new Handler(Looper.getMainLooper()).post(() -> datastore.getTopRandomMessages(5).observe(service.get(), messages1 -> {
                    assertThat(messages1.size(), is(5));
                    testRunning = false;
                    System.out.println("done");
                }));
            });
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            blockForThread();
        } catch(ScatterbrainDatastore.DatastoreInsertException e) {
            Assert.fail();
        }
    }

}
