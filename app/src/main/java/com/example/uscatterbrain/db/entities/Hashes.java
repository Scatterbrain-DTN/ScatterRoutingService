package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "hashes")
public class Hashes {
    @PrimaryKey(autoGenerate = true)
    public long hashID;

    @ColumnInfo
    public byte[] hash;
}
