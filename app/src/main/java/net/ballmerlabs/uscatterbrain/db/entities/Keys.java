package net.ballmerlabs.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "keys")
public class Keys {
    @PrimaryKey(autoGenerate = true)
    public long keyID;

    @ColumnInfo
    public long identityFK;

    @ColumnInfo
    public String key;

    @ColumnInfo
    public byte[] value;
}
