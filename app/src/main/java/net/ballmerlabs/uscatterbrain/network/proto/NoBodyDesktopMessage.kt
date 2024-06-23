package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.util.hashAsUUID
import proto.Scatterbrain.Event.NoBodyMessage
import proto.Scatterbrain.MessageType
import java.util.UUID

@SbPacket(messageType = MessageType.MESSAGE)
data class NoBodyDesktopMessage(
    val p: NoBodyMessage,
    val fromFingerprint: UUID? = p.fromFingerprint?.toUuid(),
    val toFingerprint: UUID? = p.toFingerprint?.toUuid(),
    val mime: String = p.mime,
    val id: UUID = p.id.toUuid(),
    val application: String = p.application,
    val extension: String = p.extension,
) : ScatterSerializable<NoBodyMessage>(p, MessageType.MESSAGE) {
    companion object {

        fun fromDbMessages(
            v: DbMessage,
        ): NoBodyDesktopMessage {
            val from = v.fromFingerprint.firstOrNull()?.toProto()
            val to = v.toFingerprint.firstOrNull()?.toProto()
            var hb = NoBodyMessage.newBuilder()
                .setApplication(v.application)
                .setExtension(v.extension)
                .setMime(v.mime)
                .setSendDate(v.sendDate)
                .setReceiveDate(java.util.Date().time)
                .setIsFile(v.isFile)
                .setId(hashAsUUID(v.message.fileGlobalHash).toProto())
                .setFileName(v.userFilename)

            if (from != null) {
                hb = hb.setFromFingerprint(from)
            }
            if (to != null) {
                hb = hb.setToFingerprint(to)
            }

            return NoBodyDesktopMessage(hb.build())
        }

        fun fromPacket(packet: ScatterMessage): NoBodyDesktopMessage {
            return NoBodyDesktopMessage(
                NoBodyMessage.newBuilder()
                    .setFromFingerprint(packet.fromFingerprint?.toProto())
                    .setToFingerprint(packet.toFingerprint?.toProto())
                    .setMime(packet.mime)
                    .setId(packet.id.uuid.toProto())
                    .setApplication(packet.application)
                    .setExtension(packet.extension)
                    .build()
            )
        }
    }

    override fun validate(): Boolean {
        return application.length <= net.ballmerlabs.scatterproto.MAX_APPLICATION_NAME && net.ballmerlabs.scatterproto.isValidFilename(
            extension
        )
                && mime.length <= net.ballmerlabs.scatterproto.MAX_FILENAME
    }

}