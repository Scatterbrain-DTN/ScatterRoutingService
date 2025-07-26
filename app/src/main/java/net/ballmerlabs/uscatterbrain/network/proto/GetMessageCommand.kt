package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import scatterbrain.Scatterbrain
import scatterbrain.Scatterbrain.GetMessagesCmd
import scatterbrain.Scatterbrain.GetMessagesCmd.TimeRange
import scatterbrain.Scatterbrain.GetMessagesCmd.TimeSliceCase

import java.util.Date
import net.ballmerlabs.scatterproto.*
import scatterbrain.Scatterbrain.MessageType

data class TimeSlice(
    val fromval: Date?,
    val toval: Date?
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
                        fromval =  if (packet.sendDate.startPointCase == TimeRange.StartPointCase.START)
                            Date(packet.sendDate.start)
                        else
                            null,
                        toval = if (packet.sendDate.endPointCase == TimeRange.EndPointCase.END)
                            Date(packet.sendDate.end)
                        else
                            null
                    )
                else
                    null

    val receiveDate: TimeSlice?
        get() = if(packet.timeSliceCase == TimeSliceCase.RECEIVEDATE)
            TimeSlice(
                fromval =  if (packet.receiveDate.startPointCase == TimeRange.StartPointCase.START)
                    Date(packet.receiveDate.start)
                else
                    null,
                toval = if (packet.receiveDate.endPointCase == TimeRange.EndPointCase.END)
                    Date(packet.receiveDate.end)
                else
                    null
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