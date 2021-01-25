package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "identities")
public class KeylessIdentity {

    public KeylessIdentity() {

    }

    @PrimaryKey(autoGenerate = true)
    public long identityID;

    @ColumnInfo(name = "givenname")
    public String givenName;

    @ColumnInfo(name = "publickey")
    public byte[] publicKey;

    @ColumnInfo(name = "signature")
    public byte[] signature;

    public String fingerprint;
}
