package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "diskfiles"
)
public class DiskFiles {

    @PrimaryKey(autoGenerate = true)
    public int fileID;

    @ColumnInfo
    public int ownerID;

    @ColumnInfo
    public String filepath;

    @ColumnInfo
    public byte[] sha256;
}
