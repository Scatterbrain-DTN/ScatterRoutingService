package net.ballmerlabs.uscatterbrain.network

import net.ballmerlabs.uscatterbrain.ScatterProto

class AckPacket(ack: ScatterProto.Ack) : ScatterSerializable<ScatterProto.Ack>(ack) {

    val status: Int
        get() = packet.status

    val message: String?
        get() = if (packet.messageCase.equals(ScatterProto.Ack.MessageCase.TEXT))
            packet.text
        else
            null

    val success: Boolean
        get() = packet.success

    override val type: PacketType
        get() = PacketType.TYPE_ACK

    /**
     * Builder for constructing an AckPacket
     * @param success success value for this operation
     */
    data class Builder(
            private var success: Boolean,
            private var message: String? = null,
            private var status: Int = 0
    ) {
        /**
         * Sets the message for this packet
         * @param message status message to send
         */
        fun setMessage(message: String?) = apply {
            this.message = message
        }


        /**
         * Sets the status code for this packet
         * @param status status code to set
         */
        fun setStatus(status: Int) = apply {
            this.status = status
        }


        fun build(): AckPacket {
            val builder = ScatterProto.Ack.newBuilder().setStatus(status).setSuccess(success)
            if(message != null) {
                builder.text = message
            }
            return AckPacket(builder.build())
        }
    }

    companion object {
        @JvmStatic
        fun newBuilder(success: Boolean): Builder {
            return Builder(success)
        }

        class Parser: ScatterSerializable.Companion.Parser<ScatterProto.Ack, AckPacket>(ScatterProto.Ack.parser())

        fun parser(): Parser {
            return Parser()
        }
    }
}