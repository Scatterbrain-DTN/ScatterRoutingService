package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "hashes")
public class Hashes {
    @PrimaryKey(autoGenerate = true)
    private long hashID;

    @ColumnInfo
    private byte[] hash;

    public long getHashID() {
        return hashID;
    }

    public byte[] getHash() {
        return hash;
    }

    public void setHashID(long hashID) {
        this.hashID = hashID;
    }

    public void setHash(byte[] hash) {
        this.hash = hash;
    }
}
