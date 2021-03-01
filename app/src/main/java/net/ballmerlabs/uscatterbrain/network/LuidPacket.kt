package net.ballmerlabs.uscatterbrain.network

import android.util.Log
import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.Luid
import net.ballmerlabs.uscatterbrain.ScatterProto.Luid.hashed
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.nio.ByteBuffer
import java.util.*

class LuidPacket : ScatterSerializable {
    private val mLuid: Luid
    val isHashed: Boolean
    private var luidtag: UUID? = null

    private constructor(builder: Builder) {
        isHashed = builder.enablehash
        mLuid = if (builder.enablehash) {
            val hashed = hashed.newBuilder()
                    .setHash(ByteString.copyFrom(calculateHashFromUUID(builder.uuid)))
                    .setProtoversion(builder.version)
                    .build()
            Luid.newBuilder()
                    .setValHash(hashed)
                    .build()
        } else {
            val u = protoUUIDfromUUID(builder.uuid)
            Luid.newBuilder()
                    .setValUuid(u)
                    .build()
        }
    }

    private constructor(`is`: InputStream) {
        mLuid = CRCProtobuf.parseFromCRC(Luid.parser(), `is`)
        isHashed = mLuid.valCase == Luid.ValCase.VAL_HASH
    }

    val hash: ByteArray
        get() = if (isHashed) {
            mLuid.valHash.toByteArray()
        } else {
            ByteArray(0)
        }

    val protoVersion: Int
        get() = if (!isHashed) {
            -1
        } else {
            mLuid.valHash.protoversion
        }

    //note: this only is safe because crypto_generichash_BYTES_MIN is 16
    val hashAsUUID: UUID?
        get() {
            val h = mLuid.valHash.toByteArray()
            return if (h.size != GenericHash.BYTES) {
                Log.e("debug", "hash size wrong: ${h.size}")
                null
            } else if (isHashed) {
                val buf = ByteBuffer.wrap(h)
                //note: this only is safe because crypto_generichash_BYTES_MIN is 16
                UUID(buf.long, buf.long)
            } else {
                Log.e("debug", "isHashed not set")
                null
            }
        }

    fun verifyHash(packet: LuidPacket?): Boolean {
        return if (packet!!.isHashed == isHashed) {
            false
        } else if (isHashed) {
            val hash = calculateHashFromUUID(packet.luid)
            Arrays.equals(hash, hash)
        } else {
            val hash = calculateHashFromUUID(luid)
            Arrays.equals(hash, packet.hash)
        }
    }

    val valCase: Luid.ValCase
        get() = mLuid.valCase

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(mLuid, os)
                os.toByteArray()
            } catch (ignored: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(mLuid, os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_LUID

    override fun tagLuid(luid: UUID?) {
        luidtag = luid
    }

    override val luid: UUID
        get() = protoUUIDtoUUID(mLuid.valUuid)

    class Builder {
        var uuid: UUID? = null
        var enablehash = false
        var version = -1
        fun enableHashing(protoversion: Int): Builder {
            enablehash = true
            version = protoversion
            return this
        }

        fun setLuid(uuid: UUID?): Builder {
            this.uuid = uuid
            return this
        }

        fun build(): LuidPacket {
            requireNotNull(uuid) { "uuid required" }
            return LuidPacket(this)
        }

    }

    companion object {
        private fun protoUUIDfromUUID(uuid: UUID?): ScatterProto.UUID {
            return ScatterProto.UUID.newBuilder()
                    .setLower(uuid!!.leastSignificantBits)
                    .setUpper(uuid.mostSignificantBits)
                    .build()
        }

        private fun protoUUIDtoUUID(uuid: ScatterProto.UUID): UUID {
            return UUID(uuid.upper, uuid.lower)
        }

        private fun calculateHashFromUUID(uuid: UUID?): ByteArray {
            val hashbytes = ByteArray(GenericHash.BYTES)
            val uuidBuffer = ByteBuffer.allocate(16)
            uuidBuffer.putLong(uuid!!.mostSignificantBits)
            uuidBuffer.putLong(uuid.leastSignificantBits)
            val uuidbytes = uuidBuffer.array()
            LibsodiumInterface.sodium.crypto_generichash(
                    hashbytes,
                    hashbytes.size,
                    uuidbytes,
                    uuidbytes.size.toLong(),
                    null,
                    0
            )
            return hashbytes
        }

        fun parseFrom(inputStream: InputStream): Single<LuidPacket> {
            return Single.fromCallable { LuidPacket(inputStream) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<LuidPacket> {
            val observer = InputStreamObserver(4096) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray?>): Single<LuidPacket> {
            val observer = InputStreamFlowableSubscriber(4096) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun newBuilder(): Builder {
            return Builder()
        }
    }
}