package com.example.uscatterbrain.db;

import android.content.Context;
import android.os.AsyncTask;

import androidx.room.Entity;
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
        for(ScatterMessage message : messages) {
            if (message.getIdentity() == null || message.getFiles() == null) {
                throw new DatastoreInsertException();
            }
        }

        mDatastore.scatterMessageDao().insertMessages(messages);
    }

    public void insertMessage(ScatterMessage message) throws DatastoreInsertException {
        if(message.getIdentity() == null || message.getFiles() == null) {
            throw new DatastoreInsertException();
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                mDatastore.scatterMessageDao().insertMessages(message);
            }
        });
    }

    public  void insertIdentity(Identity[] identities) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                mDatastore.identityDao().insertAll(identities);
            }
        });
    }

    public void insertIdentity(Identity identity) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                mDatastore.identityDao().insertAll(identity);
            }
        });
    }

    public void insertFile(DiskFiles files) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                mDatastore.diskFilesDao().insertAll(files);
            }
        });
    }

    public void insertFile(List<DiskFiles> files) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                mDatastore.diskFilesDao().insertAll(files);
            }
        });
    }

    public static class DatastoreInsertException extends Exception {
        public DatastoreInsertException() {
            super();
        }
    }
}
