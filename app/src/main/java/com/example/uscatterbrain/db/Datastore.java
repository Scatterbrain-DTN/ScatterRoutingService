package com.example.uscatterbrain.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.IdentityDao;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.entities.ScatterMessageDao;

@Database(entities = {ScatterMessage.class, Identity.class}, version = 1)
public abstract class Datastore extends RoomDatabase {
    public abstract IdentityDao identityDao();
    public abstract ScatterMessageDao scatterMessageDao();
}
