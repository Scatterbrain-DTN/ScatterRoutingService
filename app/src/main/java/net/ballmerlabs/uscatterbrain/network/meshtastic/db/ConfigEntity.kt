package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.Entity

@Entity(primaryKeys = ["scope", "key"])
data class ConfigEntity(
    val scope: String,
    val key: String,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConfigEntity

        if (scope != other.scope) return false
        if (key != other.key) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = scope.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}