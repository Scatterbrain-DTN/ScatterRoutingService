package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Embedded
import androidx.room.Relation

class Identity {
    @Embedded
    var identity: KeylessIdentity? = null

    @Relation(parentColumn = "identityID", entityColumn = "identityFK")
    var keys: List<Keys>? = null

    @Relation(parentColumn = "identityID", entityColumn = "identityFK")
    var clientACL: List<ClientApp>? = null
}