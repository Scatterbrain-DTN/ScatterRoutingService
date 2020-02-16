package com.example.uscatterbrain.db.entities;

import androidx.room.Embedded;
import androidx.room.Junction;
import androidx.room.Relation;
import java.util.ArrayList;
import java.util.List;

public class ScatterMessagesWithFiles {
    @Embedded public ScatterMessage message;

    @Relation(
            parentColumn = "messageID",
            entityColumn = "fileID",
            associateBy = @Junction(MessageDiskFileCrossRef.class)
    )
    public List<DiskFiles> messageDiskFiles = new ArrayList<>();
}
