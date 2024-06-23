package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain.Role
import proto.Scatterbrain.Upgrade
import java.util.UUID
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

/**
 * Wrapper class for protocol buffer upgrade message
 * @property sessionID used for referring to the previous transport module
 * @property metadata key-value data used for configuring the next transport module
 * @property provides transport module to bootstrap to
 */
@SbPacket(messageType = MessageType.UPGRADE)
class UpgradePacket(
    packet: Upgrade
) : ScatterSerializable<Upgrade>(packet, MessageType.UPGRADE) {
    /**
     * Gets session id.
     * TODO: actually use this and/or convert to uuid
     * @return the session id
     */
    val sessionID: Int
        get() = packet.sessionid

    /**
     * gets the metadata map
     */
    val metadata: Map<String, String>
        get() = packet.metadataMap

    val from: UUID
        get() = UUID.fromString(packet.metadataMap[FROM])

    val role: Role
        get() = packet.role

    /**
     * Gets provides.
     *
     * @return the provies
     */
    val provides: Provides
        get() = valToProvides(packet.provides)

    override fun validate(): Boolean {
        return metadata.size <= MAX_METADATA && metadata.values.all { v -> v.length <= net.ballmerlabs.scatterproto.MAX_METADATA_VALUE }
    }

    fun compare(other: UpgradePacket): Boolean {
        return metadata.size == other.metadata.size && metadata.filter { p -> p.key != UpgradePacket.Companion.KEY_PORT && p.key != UpgradePacket.Companion.FROM }
            .all { v -> other.metadata.containsKey(v.key) && other.metadata[v.key] == metadata[v.key] } && provides == other.provides
    }

    override fun toString(): String {
        var out = "Upgrade(\n" + "role=$role\n" + "metadata="
        for (m in metadata) {
            out += "${m.key}: ${m.value}\n"
        }
        return "$out)"
    }

    /**
     * The type Builder.
     */
    data class Builder(
        val role: Role,
        var sessionID: Int = 0,
        var provides: Provides? = null,
        var metadata: Map<String, String>? = null,
        var from: UUID? = null
    ) {

        /**
         * Sets session id.
         *
         * @param sessionID the session id
         * @return builder
         */
        fun setSessionID(sessionID: Int) = apply {
            this.sessionID = sessionID
        }

        fun setFrom(luid: UUID) = apply {
            this.from = luid
        }

        /**
         * Sets provides.
         *
         * @param provides the provides
         * @return builder
         */
        fun setProvides(provides: Provides?) = apply {
            this.provides = provides
        }

        fun setMetadata(metadata: Map<String, String>?) = apply {
            this.metadata = metadata
        }

        /**
         * Build upgrade packet.
         *
         * @return the upgrade packet
         */
        fun build(): UpgradePacket? {
            if (provides == null) return null
            if (metadata == null) {
                metadata = HashMap()
            }
            val packet =
                Upgrade.newBuilder().setProvides(
                    providesToVal(
                        provides!!
                    )
                ).setSessionid(sessionID)
                    .putAllMetadata(metadata).setFrom(this.from?.toProto())
                    //.setType(MessageType.UPGRADE)
                    .build()
            return UpgradePacket(
                packet
            )
        }
    }

    companion object {
        const val KEY_NAME = "p2p-groupname"
        const val KEY_PASSPHRASE = "p2p-passphrase"
        const val KEY_ROLE = "p2p-role"
        const val KEY_BAND = "p2p-band"
        const val KEY_PORT = "p2p-port"
        const val KEY_OWNER_ADDRESSS = "p2p-addresss"
        const val FROM = "from-luid"
        /**
         * Constructs a new builder class.
         *
         * @return the builder
         */
        fun newBuilder(role: Role): Builder {
            return Builder(
                role
            )
        }
    }
}