package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "identities")
public class Identity {

    public Identity() {

    }

    @PrimaryKey
    private long identityID;

    @ColumnInfo(name = "givenname")
    private String givenName;

    @ColumnInfo(name = "description")
    private String description;

    @ColumnInfo(name = "publickey")
    private byte[] publicKey;

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

    public String getDescription() {
        return description;
    }

    public String getGivenName() {
        return givenName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }
}
