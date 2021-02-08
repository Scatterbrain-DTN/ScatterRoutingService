package net.ballmerlabs.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class ClientApp {
    @PrimaryKey(autoGenerate = true)
    public long clientAppID;

    @ColumnInfo
    public long identityFK;

    @ColumnInfo
    public String packageName;

    @ColumnInfo
    public String packageSignature;
}
