package net.ballmerlabs.uscatterbrain.network.proto

import android.content.Context
import com.google.protobuf.ByteString
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterbrainsdk.ScatterMessage

import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid

import proto.Scatterbrain
import proto.Scatterbrain.MessageType

fun ScatterMessage.toProto(): Scatterbrain.ApiMessage {
    var builder = Scatterbrain.ApiMessage.newBuilder()
        .setApplication(application)
        .setExtension(extension)
        .setMime(mime)
        .setFileName(filename)
        .setSendDate(sendDate.time)
        .setReceiveDate(receiveDate.time)
        .setIsFile(isFile)
        .setBody(ByteString.copyFrom(body))

    val from = fromFingerprint?.toProto()
    val to = toFingerprint?.toProto()
    if (from != null) {
        builder = builder.setFromFingerprint(from)
    }

    if (to != null) {
        builder = builder.setToFingerprint(to)
    }

    return builder.build()
}

fun Scatterbrain.ApiMessage.fromProto(context: Context): ScatterMessage {
    return ScatterMessage.Builder.newInstance(context, body.toByteArray())
        .setTo(toFingerprint.toUuid())
        .setApplication(application)
        .build()
}

@SbPacket(messageType = MessageType.MESSAGE_RESPONSE)
class MessageResponse(
    packet: Scatterbrain.MessageResponse
): ScatterSerializable<Scatterbrain.MessageResponse>(packet, MessageType.MESSAGE_RESPONSE) {

    fun getMessages(context: Context): List<ScatterMessage> {
        return packet.messsageList.map { v -> v.fromProto(context) }
    }

    constructor(
        header: ApiHeader,
        messages: List<ScatterMessage>,
        respcode: Scatterbrain.RespCode
    ): this(
        Scatterbrain.MessageResponse.newBuilder()
            .addAllMesssage(messages.map { v -> v.toProto() })
            .setHeader(header.packet)
            .setCode(respcode)
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}