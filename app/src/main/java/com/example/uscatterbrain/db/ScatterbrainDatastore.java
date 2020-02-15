package com.example.uscatterbrain.db;

import android.content.Context;

import androidx.room.Room;

import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

public class ScatterbrainDatastore {

    private Datastore mDatastore;
    public static final String DATABASE_NAME = "scatterdb";

    public ScatterbrainDatastore(Context ctx) {
        mDatastore = Room.databaseBuilder(ctx, Datastore.class, DATABASE_NAME).build();
    }

    public void insertMessage(List<ScatterMessage> messages) throws DatastoreInsertException {
        for(ScatterMessage message : messages) {
            if (message.identity == null || message.files == null) {
                throw new DatastoreInsertException();
            }
        }

        mDatastore.scatterMessageDao().insertMessages(messages);
    }

    public void insertMessage(ScatterMessage message) throws DatastoreInsertException {
        if(message.identity == null || message.files == null) {
            throw new DatastoreInsertException();
        }

        mDatastore.scatterMessageDao().insertMessages(message);
    }

    public  void insertIdentity(Identity[] identities) {
        mDatastore.identityDao().insertAll(identities);
    }

    public void insertIdentity(Identity identity) {
        mDatastore.identityDao().insertAll(identity);
    }

    public void insertFile(DiskFiles files) {
        mDatastore.diskFilesDao().insertAll(files);
    }

    public void insertFile(List<DiskFiles> files) {
        mDatastore.diskFilesDao().insertAll(files);
    }

    public void insertObject(DatastoreEntity et) throws DatastoreInsertException {
        DatastoreEntity.entityType type = et.getType();

        switch(type) {
            case TYPE_MESSAGE:
                insertMessage((ScatterMessage)et);
                break;
            case TYPE_IDENTITY:
                insertIdentity((Identity)et);
                break;

            case TYPE_FILE:
                insertFile((DiskFiles) et);
                break;

            default:
                throw new DatastoreInsertException();
        }
    }

    public static class DatastoreInsertException extends Exception {
        public DatastoreInsertException() {
            super();
        }
    }
}
