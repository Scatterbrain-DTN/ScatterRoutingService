package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

import com.example.uscatterbrain.db.DatastoreEntity;

@Entity(primaryKeys = {"messageID", "fileID"}, indices = {@Index("messageID"), @Index("fileID")})
public class MessageDiskFileCrossRef implements DatastoreEntity {
    @ColumnInfo(name = "messageID")
    public long messageID;

    @ColumnInfo(name = "fileID")
    public long fileID;

    @Override
    public entityType getType() {
        return null;
    }
}
