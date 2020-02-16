package com.example.uscatterbrain;

import androidx.lifecycle.Observer;
import androidx.room.Room;

import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.entities.ScatterMessagesWithFiles;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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


    @Test
    public void dataStoreInitializesSuccessfully() {
        Datastore db = buildDB();
        db.close();
    }

    @Test
    public void test() {
        Datastore db = buildDB();
        ScatterRoutingService service = new ScatterRoutingService();

        ScatterMessage sm = new ScatterMessage(new Identity(), new byte[5]);
        sm.addFile(new DiskFiles());
        List<ScatterMessage> sms = new ArrayList<ScatterMessage>();
        sms.add(sm);

        db.scatterMessageDao().insertMessages(sms);

        db.scatterMessageDao().getMessagesWithFiles().observe(service, new Observer<List<ScatterMessagesWithFiles>>() {
            @Override
            public void onChanged(List<ScatterMessagesWithFiles> sff) {
                assertThat(sff.size(), is(1));
                assertThat(sff.get(0).messageDiskFiles.size(), is(1));

                db.clearAllTables();
                db.close();
            }
        });
    }

    @Test
    public void publicApiInsertsMessage() {
        ScatterbrainDatastore datastore = new ScatterbrainDatastore(RuntimeEnvironment.application);
        ScatterMessage sm = new ScatterMessage(new Identity(), new byte[5]);
        sm.addFile(new DiskFiles());
        List<ScatterMessage> sms = new ArrayList<ScatterMessage>();

        try {
            datastore.insertMessage(sm);
        }
        catch(ScatterbrainDatastore.DatastoreInsertException e) {
            fail();
        }
    }
}
