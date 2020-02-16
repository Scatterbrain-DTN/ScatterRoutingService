package com.example.uscatterbrain.db;

import android.content.Context;
import android.os.AsyncTask;

import androidx.lifecycle.LiveData;
import androidx.room.Entity;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.Room;

import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ScatterbrainDatastore {

    private Datastore mDatastore;
    private Executor executor;
    public static final String DATABASE_NAME = "scatterdb";

    public ScatterbrainDatastore(Context ctx) {
        mDatastore = Room.databaseBuilder(ctx, Datastore.class, DATABASE_NAME).build();
        executor = Executors.newSingleThreadExecutor();
    }

    public void insertMessage(List<ScatterMessage> messages) throws DatastoreInsertException {
        insertMessage(messages, new DatastoreInsertUpdateCallback<List<Long>>() {
            @Override
            public void onRowUpdate(List<Long> rowids) {
                //noop
            }
        });
    }

    public void insertMessage(List<ScatterMessage> messages, DatastoreInsertUpdateCallback<List<Long>> callback) throws DatastoreInsertException {
        for(ScatterMessage message : messages) {
            if (message.getIdentity() == null || message.getFiles() == null) {
                throw new DatastoreInsertException();
            }
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<Long> ids = mDatastore.scatterMessageDao().insertMessages(messages);
                callback.onRowUpdate(ids);
            }
        });
    }

    public void insertMessage(ScatterMessage message) throws DatastoreInsertException {
        insertMessage(message, new DatastoreInsertUpdateCallback<Long>() {
            @Override
            public void onRowUpdate(Long rowids) {
                //noop
            }
        });
    }

    public void insertMessage(ScatterMessage message, DatastoreInsertUpdateCallback<Long> callback) throws DatastoreInsertException {
        if(message.getIdentity() == null || message.getFiles() == null) {
            throw new DatastoreInsertException();
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                Long id = mDatastore.scatterMessageDao().insertMessages(message);
                callback.onRowUpdate(id);
            }
        });
    }

    public void insertIdentity(Identity[] identities) {
        insertIdentity(identities, new DatastoreInsertUpdateCallback<List<Long>>() {
            @Override
            public void onRowUpdate(List<Long> rowids) {
                //noop
            }
        });
    }

    public  void insertIdentity(Identity[] identities, DatastoreInsertUpdateCallback<List<Long>> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<Long> ids = mDatastore.identityDao().insertAll(identities);
                callback.onRowUpdate(ids);
            }
        });
    }

    public void insertIdentity(Identity identity) {
        insertIdentity(identity, new DatastoreInsertUpdateCallback<Long>() {
            @Override
            public void onRowUpdate(Long rowids) {
                //noop
            }
        });
    }

    public void insertIdentity(Identity identity, DatastoreInsertUpdateCallback<Long> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<Long> ids = mDatastore.identityDao().insertAll(identity);
                callback.onRowUpdate(ids.get(0));
            }
        });
    }

    public void insertFile(DiskFiles files) {
        insertFile(files, new DatastoreInsertUpdateCallback<Long>() {
            @Override
            public void onRowUpdate(Long rowids) {
                //noop
            }
        });
    }

    public void insertFile(DiskFiles files, DatastoreInsertUpdateCallback<Long> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<Long> ids = mDatastore.diskFilesDao().insertAll(files);
                callback.onRowUpdate(ids.get(0));
            }
        });
    }

    public void insertFile(List<DiskFiles> files) {
        insertFile(files, new DatastoreInsertUpdateCallback<List<Long>>() {
            @Override
            public void onRowUpdate(List<Long> rowids) {
                //noop
            }
        });
    }

    public void insertFile(List<DiskFiles> files, DatastoreInsertUpdateCallback<List<Long>> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
               List<Long> ids = mDatastore.diskFilesDao().insertAll(files);
               callback.onRowUpdate(ids);
            }
        });
    }

    public LiveData<List<ScatterMessage>> getTopRandomMessages(int count) {
        return this.mDatastore.scatterMessageDao().getTopRandom(count);
    }

    public LiveData<List<ScatterMessage>> getMessagesByIdentity(Identity id) {
        return this.mDatastore.scatterMessageDao().getByIdentity(id.getIdentityID());
    }

    public static class DatastoreInsertException extends Exception {
        public DatastoreInsertException() {
            super();
        }
    }

    public interface DatastoreInsertUpdateCallback<T> {
        void onRowUpdate(T rowids);
    }
}
