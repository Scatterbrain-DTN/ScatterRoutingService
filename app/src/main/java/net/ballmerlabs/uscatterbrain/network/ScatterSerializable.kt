package net.ballmerlabs.uscatterbrain.network

import com.google.protobuf.ByteString
import io.reactivex.Completable
import io.reactivex.Flowable
import java.io.OutputStream
import java.util.*

interface ScatterSerializable {
    enum class PacketType {
        TYPE_ACK, TYPE_BLOCKSEQUENCE, TYPE_BLOCKHEADER, TYPE_IDENTITY, TYPE_ADVERTISE, TYPE_UPGRADE, TYPE_ELECT_LEADER, TYPE_LUID, TYPE_DECLARE_HASHES
    }

    val bytes: ByteArray
    val byteString: ByteString
    fun writeToStream(os: OutputStream): Completable
    fun writeToStream(fragsize: Int): Flowable<ByteArray>
    val type: PacketType
    fun tagLuid(luid: UUID?)
    val luid: UUID?
}