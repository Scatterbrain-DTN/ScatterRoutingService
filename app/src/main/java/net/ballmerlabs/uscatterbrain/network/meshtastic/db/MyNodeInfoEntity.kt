package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.meshtastic.proto.MyNodeInfo

@Entity(tableName = "my_node_info")
data class MyNodeInfoEntity(
    @ColumnInfo
    val scope: String,
    @ColumnInfo(name = "my_node_info_unknown_fields")
    val unknownFields: ByteArray,
    @PrimaryKey
    val myNodeNum: Int,
    val deviceId: ByteArray,
    @Embedded
    val firmwareEdition: FirmwareEditionEntity,
    val minAppVersion: Int,
    val nodedbCount: Int,
    val pioEnv: String,
    val rebootCount: Int

) {
    constructor(scope: String, info: MyNodeInfo): this(
        unknownFields = info.unknownFields.toByteArray(),
        myNodeNum = info.my_node_num,
        deviceId = info.device_id.toByteArray(),
        firmwareEdition = FirmwareEditionEntity(info.firmware_edition),
        minAppVersion = info.min_app_version,
        nodedbCount = info.nodedb_count,
        pioEnv = info.pio_env,
        rebootCount =  info.reboot_count,
        scope = scope
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MyNodeInfoEntity

        if (myNodeNum != other.myNodeNum) return false
        if (minAppVersion != other.minAppVersion) return false
        if (nodedbCount != other.nodedbCount) return false
        if (rebootCount != other.rebootCount) return false
        if (scope != other.scope) return false
        if (!unknownFields.contentEquals(other.unknownFields)) return false
        if (!deviceId.contentEquals(other.deviceId)) return false
        if (firmwareEdition != other.firmwareEdition) return false
        if (pioEnv != other.pioEnv) return false

        return true
    }

    override fun hashCode(): Int {
        var result = myNodeNum
        result = 31 * result + minAppVersion
        result = 31 * result + nodedbCount
        result = 31 * result + rebootCount
        result = 31 * result + scope.hashCode()
        result = 31 * result + unknownFields.contentHashCode()
        result = 31 * result + deviceId.contentHashCode()
        result = 31 * result + firmwareEdition.hashCode()
        result = 31 * result + pioEnv.hashCode()
        return result
    }
}