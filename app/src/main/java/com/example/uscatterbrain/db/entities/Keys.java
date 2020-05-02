package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "keys")
public class Keys {
    @PrimaryKey(autoGenerate = true)
    private long keyID;

    @ColumnInfo
    private long identityFK;

    @ColumnInfo
    private String key;

    @ColumnInfo
    private byte[] value;


    public String getKey() {
        return key;
    }

    public byte[] getValue() {
        return value;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setValue(byte[] value) {
        this.value = value;
    }

    public long getKeyID() {
        return keyID;
    }

    public void setKeyID(long keyID) {
        this.keyID = keyID;
    }

    public long getIdentityFK() {
        return identityFK;
    }

    public void setIdentityFK(long identityFK) {
        this.identityFK = identityFK;
    }
}
