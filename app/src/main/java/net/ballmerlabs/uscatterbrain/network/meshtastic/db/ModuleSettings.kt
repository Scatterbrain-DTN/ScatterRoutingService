package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.ModuleSettings

@Entity
data class ModuleSettings(
    @Ignore
    private val moduleSettings: ModuleSettings,
    val positionPrecision: Int =moduleSettings.position_precision,
    val isMuted: Boolean = moduleSettings.is_muted,
    @ColumnInfo(name = "module_unknown_fields")
    val unknownFields: ByteArray? = moduleSettings.unknownFields.toByteArray(),
) {



    constructor(
        positionPrecision: Int ,
        isMuted: Boolean,
        unknownFields: ByteArray?
    ): this(
        moduleSettings = ModuleSettings(
            position_precision = positionPrecision,
            is_muted = isMuted,
            unknownFields = unknownFields?.toByteString()?: ByteString.EMPTY
        )
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as net.ballmerlabs.uscatterbrain.network.meshtastic.db.ModuleSettings

        if (positionPrecision != other.positionPrecision) return false
        if (isMuted != other.isMuted) return false
        if (moduleSettings != other.moduleSettings) return false
        if (!unknownFields.contentEquals(other.unknownFields)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = positionPrecision
        result = 31 * result + isMuted.hashCode()
        result = 31 * result + moduleSettings.hashCode()
        result = 31 * result + (unknownFields?.contentHashCode() ?: 0)
        return result
    }
}