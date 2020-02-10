package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(primaryKeys = {"messageID", "fileID"}, indices = {@Index("messageID"), @Index("fileID")})
public class MessageDiskFileCrossRef {
    @ColumnInfo(name = "messageID")
    public long messageID;

    @ColumnInfo(name = "fileID")
    public long fileID;
}
