package net.ballmerlabs.uscatterbrain.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import net.ballmerlabs.uscatterbrain.db.entities.Hashes;
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage;
import net.ballmerlabs.uscatterbrain.db.entities.KeylessIdentity;
import net.ballmerlabs.uscatterbrain.db.entities.IdentityDao;
import net.ballmerlabs.uscatterbrain.db.entities.Keys;
import net.ballmerlabs.uscatterbrain.db.entities.MessageHashCrossRef;
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessageDao;

@Database(entities = {
            HashlessScatterMessage.class,
            KeylessIdentity.class,
            Hashes.class,
            MessageHashCrossRef.class,
            Keys.class,
        }, version = 2, exportSchema = false)
public abstract class Datastore extends RoomDatabase {
    public abstract IdentityDao identityDao();
    public abstract ScatterMessageDao scatterMessageDao();
}
