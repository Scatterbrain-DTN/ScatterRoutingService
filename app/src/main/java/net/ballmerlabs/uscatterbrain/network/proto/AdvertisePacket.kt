package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.MAX_PROVIDES_LIST
import net.ballmerlabs.scatterproto.Provides
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.providesToValArray
import net.ballmerlabs.scatterproto.valToProvidesArray
import scatterbrain.Bootstrap.Advertise
import scatterbrain.Merkle.DeclareHashesMode
import scatterbrain.Scatterbrain.MessageType

/**
 * Wrapper class for advertisepacket protocol buffer message.
 *
 * @property provides list of transport modules provided by sender
 */
@SbPacket(messageType = MessageType.ADVERTISE)
class AdvertisePacket(packet: Advertise) :
    ScatterSerializable<Advertise>(packet, MessageType.ADVERTISE) {

    val provides = valToProvidesArray(packet.providesList)

    val mode = packet.mode

    override fun validate(): Boolean {
        return provides.size <= MAX_PROVIDES_LIST
    }

    /**
     * builder for advertise packet
     */
    data class Builder(
        var provides: List<Provides>? = null,
        var mode: DeclareHashesMode = DeclareHashesMode.MERKLEPROOF
    )
    /**
     * Instantiates a new Builder.
     */
    {
        /**
         * Sets provides.
         *
         * @param provides scatterbrain provides enum
         * @return builder
         */
        fun setProvides(provides: List<Provides>?) = apply {
            this.provides = provides
        }

        /**
         * Sets the declarehashes mode flag for future message transfer
         * @param mode to set, defaults to MERKLEPROOF
         * @return builder
         */
        fun setDeclareHashesMode(mode: DeclareHashesMode) = apply {
            this.mode = mode
        }

        /**
         * Build advertise packet.
         *
         * @return the advertise packet
         */
        fun build(): AdvertisePacket? {
            return if (provides == null) null else AdvertisePacket(
                    Advertise.newBuilder()
                        //  .setType(ScatterProto.MessageType.ADVERTISE)
                        .addAllProvides(providesToValArray(provides))
                        .build()
            )
        }

    }

    companion object {
        fun valToProvides(`val`: Int): Provides {
            for (p in Provides.entries) {
                if (p.`val` == `val`) {
                    return p
                }
            }
            return Provides.INVALID
        }

        fun providesToVal(provides: Provides): Int {
            return provides.`val`
        }

        /**
         * New builder class
         *
         * @return the builder
         */
        fun newBuilder(): Builder {
            return Builder()
        }
    }
}