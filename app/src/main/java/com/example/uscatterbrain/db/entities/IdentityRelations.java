package com.example.uscatterbrain.db.entities;

import androidx.room.Embedded;
import androidx.room.Relation;

import java.util.List;

public class IdentityRelations {
    @Embedded
    public Identity identity;

    @Relation(
            parentColumn = "identityID",
            entityColumn = "identityFK"
    )
    public List<Keys> keys;
}
