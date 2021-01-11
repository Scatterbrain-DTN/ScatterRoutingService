package com.example.uscatterbrain.db.entities;

import androidx.room.Embedded;
import androidx.room.Junction;
import androidx.room.Relation;
import java.util.ArrayList;
import java.util.List;

public class ScatterMessage {
    @Embedded public HashlessScatterMessage message;

    @Relation(
            parentColumn = "messageID",
            entityColumn = "hashID",
            associateBy = @Junction(MessageHashCrossRef.class)
    )
    public List<Hashes> messageHashes;
}
