package com.example.uscatterbrain.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.DiskFilesDao;
import com.example.uscatterbrain.db.entities.Hashes;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.IdentityDao;
import com.example.uscatterbrain.db.entities.IdentityKeyCrossRef;
import com.example.uscatterbrain.db.entities.Keys;
import com.example.uscatterbrain.db.entities.MessageDiskFileCrossRef;
import com.example.uscatterbrain.db.entities.MessageDiskFileCrossRefDao;
import com.example.uscatterbrain.db.entities.MessageHashCrossRef;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.entities.ScatterMessageDao;

@Database(entities = {
            ScatterMessage.class,
            Identity.class,
            DiskFiles.class,
            MessageDiskFileCrossRef.class,
            Hashes.class,
            MessageHashCrossRef.class,
            Keys.class,
            IdentityKeyCrossRef.class
        }, version = 1, exportSchema = false)
public abstract class Datastore extends RoomDatabase {
    public abstract IdentityDao identityDao();
    public abstract ScatterMessageDao scatterMessageDao();
    public abstract DiskFilesDao diskFilesDao();
    public abstract MessageDiskFileCrossRefDao messageDiskFileCrossRefDao();
}
