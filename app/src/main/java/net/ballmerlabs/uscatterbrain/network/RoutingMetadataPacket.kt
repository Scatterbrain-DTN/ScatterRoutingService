package net.ballmerlabs.uscatterbrain.network

import com.google.protobuf.ByteString
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.RoutingMetadata
import java.util.*

/**
 * wrapper class for RoutingMetadata protobuf message
 *
 * NOTE: this is only a stubbed placeholder message for now
 */
class RoutingMetadataPacket(packet: RoutingMetadata):
        ScatterSerializable<RoutingMetadata>(packet) {
    private val metadataMap = HashMap<UUID, ByteArray>()

    @Synchronized
    private fun addMap(`val`: Map<String, ByteString>) {
        metadataMap.clear()
        for ((key, value) in `val`) {
            metadataMap[UUID.fromString(key)] = value.toByteArray()
        }
    }

    @get:Synchronized
    private val map: Map<String, ByteString>
        get() {
            val result: MutableMap<String, ByteString> = HashMap()
            for ((key, value) in metadataMap) {
                result[key.toString()] = ByteString.copyFrom(value)
            }
            return result
        }

    val metadata: Map<UUID, ByteArray>
        get() = metadataMap

    val isEmpty: Boolean
        get() = packet.endofstream

    override val type: PacketType
        get() = PacketType.TYPE_DECLARE_HASHES

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
                    .putAllKeyval(map.mapKeys { entry -> entry.key.toString()}.mapValues { v -> ByteString.copyFrom(v.value) })
                    .setId(ScatterProto.UUID.newBuilder()
                            .setLower(uuid!!.leastSignificantBits)
                            .setUpper(uuid!!.mostSignificantBits))
                    .setEndofstream(empty)
                    .build()
            return RoutingMetadataPacket(packet)
        }

    }

    companion object {
        fun newBuilder(): Builder {
            return Builder()
        }
        class Parser : ScatterSerializable.Companion.Parser<RoutingMetadata, RoutingMetadataPacket>(RoutingMetadata.parser())
        fun parser(): Parser {
            return Parser()
        }

    }
}