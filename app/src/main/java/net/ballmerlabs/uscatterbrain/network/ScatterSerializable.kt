package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import io.reactivex.Completable
import io.reactivex.Flowable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.*

/**
 * base class for all protobuf messages
 */
abstract class ScatterSerializable<T : MessageLite>(
        val packet: T
) {
    enum class PacketType {
        TYPE_ACK, TYPE_BLOCKSEQUENCE, TYPE_BLOCKHEADER, TYPE_IDENTITY, TYPE_ADVERTISE, TYPE_UPGRADE, TYPE_ELECT_LEADER, TYPE_LUID, TYPE_DECLARE_HASHES
    }

    var luid: UUID? = null

    val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(packet, os)
                os.toByteArray()
            } catch (e: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(packet, os) }
    }

    fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    abstract val type: PacketType


    companion object {
        abstract class Parser<T: MessageLite, V: ScatterSerializable<T>>(
                val parser: com.google.protobuf.Parser<T>
        )
    }
}