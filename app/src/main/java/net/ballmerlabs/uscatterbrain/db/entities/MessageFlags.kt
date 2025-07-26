package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.google.protobuf.ByteString
import scatterbrain.Transfer

@Entity(
    tableName = "message_flags",
    foreignKeys = [
        ForeignKey(
            parentColumns = [ "messageID" ],
            childColumns = [ "parentMessage" ],
            entity = HashlessScatterMessage::class
        )
    ]
)
data class MessageFlags(
    var parentMessage: Long? = null,
    val flagKey: Int,
    val flagBytes: ByteArray? = null,
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,
    ) {

    fun toProto(): Transfer.BlockData.MessageFlags {
        return when(flagKey) {
            Transfer.MessageFlag.FORWARD_MESHTASTIC_VALUE -> Transfer.BlockData.MessageFlags
                .newBuilder()
                .setTag(Transfer.MessageFlag.FORWARD_MESHTASTIC)
                .apply {
                    if (flagBytes != null)
                        setBlob(ByteString.copyFrom(flagBytes))
                }
                .build()
            else -> Transfer.BlockData.MessageFlags
                .newBuilder()
                .setTag(Transfer.MessageFlag.forNumber(flagKey))
                .build()
        }
    }


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MessageFlags

        if (parentMessage != other.parentMessage) return false
        if (flagKey != other.flagKey) return false
        if (id != other.id) return false
        if (flagBytes != null) {
            if (other.flagBytes == null) return false
            if (!flagBytes.contentEquals(other.flagBytes)) return false
        } else if (other.flagBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = parentMessage.hashCode()
        result = 31 * result + flagKey
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + (flagBytes?.contentHashCode() ?: 0)
        return result
    }

}