package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(primaryKeys = {"identityID", "keyID"}, indices = {@Index("identityID"), @Index("keyID")})
public class IdentityKeyCrossRef {
    @ColumnInfo(name = "identityID")
    public long identityID;

    @ColumnInfo(name = "keyID")
    public long keyID;
}
