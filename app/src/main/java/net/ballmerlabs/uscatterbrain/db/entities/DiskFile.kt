package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Embedded
import androidx.room.Relation

data class DiskFile(
    @Embedded
    val global: GlobalHash,
    @Relation(parentColumn = "globalhash", entityColumn = "parent")
    var messageHashes: List<Hashes>,
)