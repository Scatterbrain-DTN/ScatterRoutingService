package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Embedded;
import androidx.room.Entity;
import androidx.room.Relation;

import java.util.List;

@Entity
public class ScatterMessagesWithFiles {
    @Embedded public ScatterMessage message;

    @Relation(
            parentColumn = "messageID",
            entityColumn = "ownerID",
            entity = DiskFiles.class
    )
    List<DiskFiles> messageDiskFiles;

    @Relation(
            parentColumn = "messageID",
            entityColumn = "identityID",
            entity = Identity.class
    )
    List<Identity> messageIdentities;
}
