package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.example.uscatterbrain.db.DatastoreEntity;

@Entity(
        tableName = "diskfiles"
)
public class DiskFiles implements DatastoreEntity {

    @PrimaryKey(autoGenerate = true)
    public long fileID;

    @ColumnInfo
    public int ownerID;

    @ColumnInfo
    public String filepath;

    @ColumnInfo
    public byte[] sha256;

    @Override
    public entityType getType() {
        return entityType.TYPE_FILE;
    }
}
