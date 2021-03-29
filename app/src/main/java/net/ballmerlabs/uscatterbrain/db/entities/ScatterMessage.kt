package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * helper class representing a relation between a message and its hashes
 */
data class ScatterMessage(
    @Embedded
    var message: HashlessScatterMessage,

    @Relation(parentColumn = "messageID", entityColumn = "hashID", associateBy = Junction(MessageHashCrossRef::class))
    var messageHashes: List<Hashes>
)