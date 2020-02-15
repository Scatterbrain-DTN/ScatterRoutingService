package com.example.uscatterbrain.db;

import android.content.Context;

import androidx.room.Room;

import org.greenrobot.eventbus.EventBus;

public class ScatterbrainDatastore {

    private Datastore mDatastore;
    public static final String DATABASE_NAME = "scatterdb";

    public ScatterbrainDatastore(Context ctx) {
        mDatastore = Room.databaseBuilder(ctx, Datastore.class, DATABASE_NAME).build();
    }

    public void registerEventBus() {
        EventBus.getDefault().register(this);
    }

    public void unregisterEventBus() {
        EventBus.getDefault().unregister(this);
    }

    public void insertObject(DatastoreEntity et) {

    }
}
