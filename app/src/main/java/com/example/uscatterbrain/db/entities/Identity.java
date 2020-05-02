package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Relation;

import java.util.List;

@Entity(tableName = "identities")
public class Identity {

    public Identity() {

    }

    @PrimaryKey(autoGenerate = true)
    private long identityID;

    @ColumnInfo(name = "givenname")
    private String givenName;

    @ColumnInfo(name = "publickey")
    private byte[] publicKey;

    @ColumnInfo(name = "signature")
    private byte[] signature;

    public long getIdentityID() {
        return identityID;
    }

    public void setIdentityID(long identityID) {
        this.identityID = identityID;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(byte[] publicKey) {
        this.publicKey = publicKey;
    }

    public String getGivenName() {
        return givenName;
    }

    public void setSignature(byte[] signature) { this.signature = signature; }

    public byte[] getSignature() { return signature; }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }
}
