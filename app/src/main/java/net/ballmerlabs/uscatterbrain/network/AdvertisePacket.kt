package net.ballmerlabs.uscatterbrain.network

import net.ballmerlabs.uscatterbrain.ScatterProto.Advertise

/**
 * Wrapper class for advertisepacket protocol buffer message.
 */
class AdvertisePacket(packet: Advertise) : ScatterSerializable<Advertise>(packet) {
    enum class Provides(val `val`: Int) {
        INVALID(-1), BLE(0), WIFIP2P(1);

    }

    val provides
    get() = valToProvidesArray(packet.providesList)

    override val type: PacketType
        get() = PacketType.TYPE_ADVERTISE

    /**
     * builder for advertise packet
     */
    data class Builder(
            var provides: List<Provides>? = null
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
         * Build advertise packet.
         *
         * @return the advertise packet
         */
        fun build(): AdvertisePacket? {
            return if (provides == null) null else AdvertisePacket(
                    Advertise.newBuilder()
                            .addAllProvides(providesToValArray(provides))
                            .build()
            )
        }

    }

    companion object {
        fun valToProvides(`val`: Int): Provides {
            for (p in Provides.values()) {
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
        class Parser : ScatterSerializable.Companion.Parser<Advertise, AdvertisePacket>(Advertise.parser())
        fun parser(): Parser {
            return Parser()
        }

    }
}