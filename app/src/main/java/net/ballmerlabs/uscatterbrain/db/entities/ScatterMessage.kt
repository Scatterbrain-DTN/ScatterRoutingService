package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

class ScatterMessage {
    @Embedded
    var message: HashlessScatterMessage? = null

    @Relation(parentColumn = "messageID", entityColumn = "hashID", associateBy = Junction(MessageHashCrossRef::class))
    var messageHashes: List<Hashes>? = null
}