package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class DiskFiles {

    @PrimaryKey
    public int fileID;

    @ColumnInfo
    public String filepath;

    @ColumnInfo
    public byte[] sha256;
}
