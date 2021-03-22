package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
        tableName = "keys",
        foreignKeys = [ForeignKey(
                entity = KeylessIdentity::class,
                parentColumns = arrayOf("identityID"),
                childColumns = arrayOf("identityFK"),
                onDelete = ForeignKey.CASCADE,
                onUpdate = ForeignKey.CASCADE
        )]
)
data class Keys(
    @ColumnInfo
    var key: String,

    @ColumnInfo
    var value: ByteArray
) {
    @PrimaryKey(autoGenerate = true)
    var keyID: Long? = null

    @ColumnInfo
    var identityFK: Long? = null
}


data class JustIdentiyFK(
        val identityFK: Long
)