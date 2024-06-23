package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain.Ack
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.ACK)
class AckPacket(ack: Ack) : ScatterSerializable<Ack>(ack, MessageType.ACK) {

    val status: Int
        get() = packet.status

    val message: String?
        get() = if (packet.messageCase.equals(Ack.MessageCase.TEXT))
            packet.text
        else
            null

    val success: Boolean
        get() = packet.success

    override fun validate(): Boolean {
        return (message?.length?:0) < MAX_ACK_MESSAGE
    }

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
            val builder = Ack.newBuilder().setStatus(status).setSuccess(success)
            if(message != null) {
                builder.text = message
            }
            return AckPacket(builder
           //     .setType(ScatterProto.MessageType.ACK)
                .build())
        }
    }

    companion object {
        @JvmStatic
        fun newBuilder(success: Boolean): Builder {
            return Builder(success)
        }
    }
}