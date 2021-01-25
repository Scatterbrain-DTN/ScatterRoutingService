package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "identities",
        indices = {
                @Index(value = {"fingerprint"}, unique = true)
        }
)
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

    @ColumnInfo(name = "fingerprint")
    public String fingerprint;
}
