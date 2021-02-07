package net.ballmerlabs.uscatterbrain.db.entities;

import androidx.room.Embedded;
import androidx.room.Relation;

import java.util.List;

public class Identity {
    @Embedded
    public KeylessIdentity identity;

    @Relation(
            parentColumn = "identityID",
            entityColumn = "identityFK"
    )
    public List<Keys> keys;
}
