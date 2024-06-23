package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain
import proto.Scatterbrain.GetMessagesCmd
import proto.Scatterbrain.GetMessagesCmd.TimeRange
import proto.Scatterbrain.GetMessagesCmd.TimeSliceCase

import java.util.Date
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

data class TimeSlice(
    val fromval: Date,
    val toval: Date
)

@SbPacket(messageType = MessageType.GET_MESSAGE)
class GetMessageCommand(
    packet: GetMessagesCmd
): ScatterSerializable<GetMessagesCmd>(packet, MessageType.GET_MESSAGE) {
    val application: String?
        get() = if (packet.maybeApplicationCase == GetMessagesCmd.MaybeApplicationCase.APPLICATION)
            packet.application
    else
        null

    val limit: Int
        get() = packet.limit

    val sendDate: TimeSlice?
        get() = if(packet.timeSliceCase == TimeSliceCase.SENDDATE)
                    TimeSlice(
                        fromval =  Date(packet.sendDate.start),
                        toval = Date(packet.sendDate.end)
                    )
                else
                    null

    val receiveDate: TimeSlice?
        get() = if(packet.timeSliceCase == GetMessagesCmd.TimeSliceCase.RECEIVEDATE)
            TimeSlice(
                fromval =  Date(packet.receiveDate.start),
                toval = Date(packet.receiveDate.end)
            )
        else
            null

    constructor(
        header: ApiHeader,
        duration: TimeRange,
        application: String,
        limit: Int
    ): this(GetMessagesCmd.newBuilder()
        .setSendDate(duration)
        .setHeader(header.packet)
        .setLimit(limit)
        .setApplication(application)
        .build())

    override fun validate(): Boolean {
        return true
    }
}