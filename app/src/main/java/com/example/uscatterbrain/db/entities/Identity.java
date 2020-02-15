package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.example.uscatterbrain.db.DatastoreEntity;

@Entity(tableName = "identities")
public class Identity implements DatastoreEntity {
    @PrimaryKey
    public long identityID;

    @ColumnInfo(name = "givenname")
    public String givenName;

    @ColumnInfo(name = "description")
    public String description;

    @ColumnInfo(name = "publickey")
    public byte[] publicKey;

    @Override
    public entityType getType() {
        return entityType.TYPE_IDENTITY;
    }
}
