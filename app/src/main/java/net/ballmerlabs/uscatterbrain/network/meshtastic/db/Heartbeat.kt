package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "heartbeats")
data class Heartbeat (
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,
    val nodeNum: Long,
    val lastHeartbeatAt: Long
)