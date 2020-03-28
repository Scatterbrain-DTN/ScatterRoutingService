package com.example.uscatterbrain.db.entities;

import androidx.room.Embedded;
import androidx.room.Junction;
import androidx.room.Relation;

import java.util.ArrayList;
import java.util.List;

public class IdentityRelations {
    @Embedded
    public Identity identity;

    @Relation(
            parentColumn = "identityID",
            entityColumn = "keyID",
            associateBy = @Junction(IdentityKeyCrossRef.class)
    )
    public List<Keys> identityKeys = new ArrayList<>();
}
