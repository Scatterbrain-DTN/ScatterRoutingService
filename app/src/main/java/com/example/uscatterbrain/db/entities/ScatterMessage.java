package com.example.uscatterbrain.db.entities;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.util.ArrayList;
import java.util.List;

import static androidx.room.ForeignKey.CASCADE;


@Entity(tableName = "messages")
public class ScatterMessage {
    @PrimaryKey(autoGenerate = true)
    public int messageID;

    @ColumnInfo
    @ForeignKey(entity = Identity.class, parentColumns = "identityID", childColumns = "identityID")
    @NonNull
    public long identityID;

    @Ignore
    public List<DiskFiles> files = new ArrayList<>();

    @Ignore
    public Identity identity;
}
