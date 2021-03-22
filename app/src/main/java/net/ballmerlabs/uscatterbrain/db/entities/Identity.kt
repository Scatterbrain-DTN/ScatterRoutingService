package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Embedded
import androidx.room.Relation

data class Identity(
    @Embedded
    var identity: KeylessIdentity,

    @Relation(parentColumn = "identityID", entityColumn = "identityFK")
    var keys: List<Keys>,

    @Relation(parentColumn = "identityID", entityColumn = "identityFK")
    var clientACL: List<ClientApp>? = null
)



data class JustFingerprint(
        val fingerprint: String
)