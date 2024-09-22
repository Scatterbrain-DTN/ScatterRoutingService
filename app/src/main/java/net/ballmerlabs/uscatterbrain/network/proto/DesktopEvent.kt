package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiIdentity
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopMessage
import net.ballmerlabs.uscatterbrain.util.hashAsUUID
import proto.Scatterbrain
import proto.Scatterbrain.SbEvent
import proto.Scatterbrain.SbEvent.NewIdentity
import proto.Scatterbrain.SbEvent.NewMessage
import proto.Scatterbrain.SbEvent.NoBodyMessage

@SbPacket(messageType = Scatterbrain.MessageType.DESKTOP_EVENT)
class DesktopEvent(
    packet: SbEvent
): ScatterSerializable<SbEvent>(packet, Scatterbrain.MessageType.DESKTOP_EVENT) {

    val messages: List<NoBodyDesktopMessage>? = if (packet.maybeEventCase == SbEvent.MaybeEventCase.NEWMESSAGE)
        packet.newMessage.messagesList.map { v -> NoBodyDesktopMessage(v) }
    else
        null

    val identities: List<DesktopApiIdentity>? = if (packet.maybeEventCase == SbEvent.MaybeEventCase.NEWIDENTITIES)
        packet.newIdentities.identitiesList.map { v -> DesktopApiIdentity(v) }
    else
        null


    companion object {
        fun fromMessages(
            messages: List<DesktopMessage>
        ) : DesktopEvent {
            return DesktopEvent(SbEvent.newBuilder()
                        .setNewMessage(NewMessage.newBuilder()
                            .addAllMessages(messages.map { v ->
                                NoBodyMessage.newBuilder()
                                    .setFromFingerprint(v.packet.fromFingerprint)
                                    .setToFingerprint(v.packet.toFingerprint)
                                    .setApplication(v.packet.application)
                                    .setExtension(v.packet.extension)
                                    .setMime(v.packet.mime)
                                    .setSendDate(v.packet.sendDate)
                                    .setReceiveDate(v.packet.receiveDate)
                                    .setIsFile(v.packet.isFile)
                                    .setId(v.packet.id)
                                    .setFileName(v.packet.fileName)
                                    .build()
                            })
                        ).build())

        }


        fun fromDbMessages(
            messages: List<DbMessage>
        ) : DesktopEvent {
            return DesktopEvent(SbEvent.newBuilder()
                .setNewMessage(NewMessage.newBuilder()
                    .addAllMessages(messages.map { v ->
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

                        if(from != null) {
                            hb = hb.setFromFingerprint(from)
                        }
                        if (to != null) {
                            hb = hb.setToFingerprint(to)
                        }

                        hb.build()

                    })
                ).build())
        }


        fun fromIdentities(
            identities: List<DesktopApiIdentity>
        ) : DesktopEvent {
            return DesktopEvent(
                    SbEvent.newBuilder()
                        .setNewIdentities(NewIdentity.newBuilder()
                            .addAllIdentities(identities.map { v -> v.packet }
                            )
                        ).build()
                    )
        }
    }

    override fun validate(): Boolean {
        return true
    }
}