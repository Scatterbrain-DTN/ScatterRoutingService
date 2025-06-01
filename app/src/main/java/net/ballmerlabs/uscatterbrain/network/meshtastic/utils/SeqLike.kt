package net.ballmerlabs.uscatterbrain.network.meshtastic.utils

import com.google.protobuf.MessageLite
import net.ballmerlabs.scatterproto.ScatterSerializable
import proto.Scatterbrain.MessageType

abstract class SeqLike<T: MessageLite>(
    packet: T,
    type: MessageType
): ScatterSerializable<T>(packet, type), Comparable<SeqLike<T>> {
    abstract val seq: Int
    abstract val end: Boolean

    override fun compareTo(other: SeqLike<T>): Int {
        return this.seq.compareTo(other.seq)
    }
}