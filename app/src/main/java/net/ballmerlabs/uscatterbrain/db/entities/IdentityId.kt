package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

@Entity
class IdentityId(
        @PrimaryKey val uuid: UUID
) {
    var message: Long = -1
}