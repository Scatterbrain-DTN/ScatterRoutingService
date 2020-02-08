package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;
import static androidx.room.ForeignKey.CASCADE;


@Entity(tableName = "messages")
public class ScatterMessage {
    @PrimaryKey
    public int messageID;

    @ColumnInfo
    public int identityID;

    @ColumnInfo
    public int[] fileID;
}
