package net.ballmerlabs.uscatterbrain.network

import net.ballmerlabs.uscatterbrain.ScatterProto.Upgrade
import java.util.*

/**
 * Wrapper class for protocol buffer upgrade message
 */
class UpgradePacket(packet: Upgrade): ScatterSerializable<Upgrade>(packet) {
    /**
     * Gets session id.
     *
     * @return the session id
     */
    val sessionID: Int
        get() = packet.sessionid

    /**
     * gets the metadata map
     */
    val metadata: Map<String, String>
        get() = packet.metadataMap

    /**
     * Gets provides.
     *
     * @return the provies
     */
    val provides: AdvertisePacket.Provides
        get() = valToProvides(packet.provides)

    override val type: PacketType
        get() = PacketType.TYPE_UPGRADE

    /**
     * The type Builder.
     */
    data class Builder(
            var sessionID: Int = 0,
            var provides: AdvertisePacket.Provides? = null,
            var metadata: Map<String, String>? = null
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

        /**
         * Sets provides.
         *
         * @param provides the provides
         * @return builder
         */
        fun setProvides(provides: AdvertisePacket.Provides?) = apply {
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
            val packet = Upgrade.newBuilder()
                    .setProvides(providesToVal(provides!!))
                    .setSessionid(sessionID)
                    .putAllMetadata(metadata)
                    .build()
            return UpgradePacket(packet)
        }
    }

    companion object {
        /**
         * Constructs a new builder class.
         *
         * @return the builder
         */
        fun newBuilder(): Builder {
            return Builder()
        }
        class Parser : ScatterSerializable.Companion.Parser<Upgrade, UpgradePacket>(Upgrade.parser())
        fun parser(): Parser {
            return Parser()
        }

    }
}