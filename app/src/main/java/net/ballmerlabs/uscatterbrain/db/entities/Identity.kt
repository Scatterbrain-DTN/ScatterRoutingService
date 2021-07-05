package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Embedded
import androidx.room.Relation
import java.util.*

/**
 * class with identity and corresponding relations.
 * represents a one-to-many relationship between KeylessIdentity
 * and multiple keys and ACLs
 */
data class Identity(
    @Embedded
    var identity: KeylessIdentity,

    @Relation(parentColumn = "identityID", entityColumn = "identityFK")
    var keys: List<Keys>,

    @Relation(parentColumn = "identityID", entityColumn = "identityFK")
    var clientACL: List<ClientApp>? = null
)


/**
 * internal class for looking up identity by fingerprint
 * in Room
 */
data class JustFingerprint(
        val fingerprint: UUID
)