package net.ballmerlabs.uscatterbrain.network.proto

import com.google.protobuf.ByteString
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.MAX_METADATA
import net.ballmerlabs.scatterproto.MAX_METADATA_VALUE
import net.ballmerlabs.scatterproto.ScatterSerializable
import scatterbrain.Scatterbrain.MessageType
import scatterbrain.Transfer.RoutingMetadata
import scatterbrain.Uuid
import java.util.UUID

/**
 * wrapper class for RoutingMetadata protobuf message
 *
 * NOTE: this is only a stubbed placeholder message for now
 */
@SbPacket(messageType = MessageType.ROUTING_METADATA)
class RoutingMetadataPacket(packet: RoutingMetadata):
        ScatterSerializable<RoutingMetadata>(packet, MessageType.ROUTING_METADATA) {
    private val metadataMap = HashMap<UUID, ByteArray>()

    private fun addMap(`val`: Map<String, ByteString>) {
        metadataMap.clear()
        for ((key, value) in `val`) {
            metadataMap[UUID.fromString(key)] = value.toByteArray()
        }
    }

    private val map: Map<String, ByteString>
        get() {
            val result: MutableMap<String, ByteString> = HashMap()
            for ((key, value) in metadataMap) {
                result[key.toString()] = ByteString.copyFrom(value)
            }
            return result
        }


    override fun validate(): Boolean {
        return metadataMap.size <= MAX_METADATA && metadataMap.values
            .all { v -> v.size <=  MAX_METADATA_VALUE }
    }

    val metadata: Map<UUID, ByteArray>
        get() = metadataMap

    val isEmpty: Boolean
        get() = packet.endofstream

    data class Builder(
            val map: MutableMap<UUID, ByteArray> = HashMap(),
            var empty: Boolean = false,
            var uuid: UUID? = null
    ) {
        fun addMetadata(map: Map<UUID, ByteArray>) = apply {
            this.map.putAll(map)
        }

        fun setEmpty() = apply {
            empty = true
        }

        fun build(): RoutingMetadataPacket {
            require(!(uuid == null && !empty)) { "uuid must be set" }
            if (empty) {
                uuid = UUID(0, 0)
            }
            val packet = RoutingMetadata.newBuilder()
                .putAllKeyval(map.mapKeys { entry -> entry.key.toString()}
                    .mapValues { v -> ByteString.copyFrom(v.value) })
                .setId(Uuid.ProtoUuid.newBuilder()
                    .setLower(uuid!!.leastSignificantBits)
                    .setUpper(uuid!!.mostSignificantBits))
                .setEndofstream(empty)
                //.setType(Scatterbrain.MessageType.ROUTING_METADATA)
                .build()
            return RoutingMetadataPacket(packet)
        }

    }

    companion object {
        fun newBuilder(): Builder {
            return Builder()
        }
    }
}