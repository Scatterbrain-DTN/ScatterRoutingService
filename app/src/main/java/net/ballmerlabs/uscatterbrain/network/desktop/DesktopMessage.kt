package net.ballmerlabs.uscatterbrain.network.desktop

import com.google.protobuf.ByteString
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import proto.Scatterbrain
import proto.Scatterbrain.MessageType
import java.util.UUID

@SbPacket(messageType = MessageType.MESSAGE)
data class DesktopMessage(
    private val p: Scatterbrain.ApiMessage,
    val fromFingerprint: UUID? = p.fromFingerprint?.toUuid(),
    val toFingerprint: UUID? = p.toFingerprint?.toUuid(),
    val mime: String = p.mime,
    val id: UUID = p.id.toUuid(),
    val body: ByteArray = p.body.toByteArray(),
    val application: String = p.application,
    val extension: String = p.extension,
): ScatterSerializable<Scatterbrain.ApiMessage>(p, MessageType.MESSAGE) {
    companion object {
        fun fromPacket(packet: ScatterMessage): DesktopMessage {
            return DesktopMessage(
                Scatterbrain.ApiMessage.newBuilder()
                .setFromFingerprint(packet.fromFingerprint?.toProto())
                .setToFingerprint(packet.toFingerprint?.toProto())
                .setMime(packet.mime)
                .setId(packet.id.uuid.toProto())
                .setBody(ByteString.copyFrom(packet.body))
                .setApplication(packet.application)
                .setExtension(packet.extension)
                .build())
        }
    }

    override fun validate(): Boolean {
        return application.length <= net.ballmerlabs.scatterproto.MAX_APPLICATION_NAME && net.ballmerlabs.scatterproto.isValidFilename(
            extension
        )
                && mime.length <= net.ballmerlabs.scatterproto.MAX_FILENAME
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DesktopMessage

        if (packet != other.packet) return false
        if (fromFingerprint != other.fromFingerprint) return false
        if (toFingerprint != other.toFingerprint) return false
        if (mime != other.mime) return false
        if (id != other.id) return false
        if (!body.contentEquals(other.body)) return false
        if (application != other.application) return false
        if (extension != other.extension) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packet.hashCode()
        result = 31 * result + (fromFingerprint?.hashCode() ?: 0)
        result = 31 * result + (toFingerprint?.hashCode() ?: 0)
        result = 31 * result + mime.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + application.hashCode()
        result = 31 * result + extension.hashCode()
        return result
    }
}