package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.ElectLeader
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.nio.ByteBuffer
import java.util.*

class ElectLeaderPacket private constructor(builder: Builder) : ScatterSerializable {
    private val mElectLeader: ElectLeader?
    private val salt: ByteArray
    override var luid: UUID? = null
        private set
    
    init {
        salt = ByteArray(GenericHash.BYTES)
        LibsodiumInterface.sodium.randombytes_buf(salt, salt.size)
        val b = ElectLeader.newBuilder()
        if (!builder.enableHashing) {
            val body = ElectLeader.Body.newBuilder()
                    .setProvides(AdvertisePacket.Companion.providesToVal(builder.provides!!))
                    .setSalt(ByteString.copyFrom(salt))
            body.tiebreakerVal = ScatterProto.UUID.newBuilder()
                    .setUpper(builder.tiebreaker!!.mostSignificantBits)
                    .setLower(builder.tiebreaker!!.leastSignificantBits)
                    .build()
            b.valBody = body.build()
        } else if (builder.hashVal == null){
            b.valHash = ByteString.copyFrom(hashFromBuilder(builder))
        } else {
            b.valHash = builder.hashVal
        }
        mElectLeader = b.build()
    }

    enum class Phase {
        PHASE_VAL, PHASE_HASH
    }

    private fun hashFromBuilder(builder: Builder): ByteArray {
        val hashbytes = ByteArray(GenericHash.BYTES)
        var bytes = ByteString.EMPTY
        bytes.concat(ByteString.copyFrom(salt))
        bytes = bytes.concat(ByteString.copyFrom(uuidToBytes(builder.tiebreaker)))
        val buffer = ByteBuffer.allocate(Integer.SIZE)
        buffer.putInt(builder.provides!!.`val`)
        bytes.concat(ByteString.copyFrom(buffer.array()))
        LibsodiumInterface.sodium.crypto_generichash(
                hashbytes,
                hashbytes.size,
                bytes.toByteArray(),
                bytes.toByteArray().size.toLong(),
                null,
                0
        )
        return hashbytes
    }

    fun hashFromPacket(): ByteArray {
        val hashbytes = ByteArray(GenericHash.BYTES)
        var bytes = ByteString.EMPTY
        bytes.concat(ByteString.copyFrom(salt))
        bytes = bytes.concat(ByteString.copyFrom(uuidToBytes(tieBreak)))
        val buffer = ByteBuffer.allocate(Integer.SIZE)
        buffer.putInt(mElectLeader!!.valBody.provides)
        bytes.concat(ByteString.copyFrom(buffer.array()))
        LibsodiumInterface.sodium.crypto_generichash(
                hashbytes,
                hashbytes.size,
                bytes.toByteArray(),
                bytes.toByteArray().size.toLong(),
                null,
                0
        )
        return hashbytes
    }

    fun verifyHash(packet: ElectLeaderPacket?): Boolean {
        return if (packet!!.isHashed == isHashed) {
            false
        } else if (isHashed) {
            val hash = packet.hashFromPacket()
            Arrays.equals(hash, hash)
        } else {
            val hash = hashFromPacket()
            Arrays.equals(hash, packet.hash)
        }
    }

    val tieBreak: UUID
        get() = UUID(
                mElectLeader!!.valBody.tiebreakerVal.upper,
                mElectLeader.valBody.tiebreakerVal.lower
        )

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    val provides: AdvertisePacket.Provides
        get() = AdvertisePacket.Companion.valToProvides(mElectLeader!!.valBody.provides)

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(mElectLeader!!, os)
                os.toByteArray()
            } catch (ignored: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(mElectLeader!!, os) }
    }

    override fun writeToStream(fragize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_ELECT_LEADER

    val isHashed: Boolean
        get() = mElectLeader!!.valCase == ElectLeader.ValCase.VAL_HASH

    val hash: ByteArray
        get() = mElectLeader!!.valHash.toByteArray()

    class Builder {
        var enableHashing = false
        var hashVal: ByteString? = null
        var provides: AdvertisePacket.Provides? = null
        var tiebreaker: UUID? = null
        fun enableHashing(): Builder {
            enableHashing = true
            return this
        }

        fun setProvides(provides: AdvertisePacket.Provides): Builder {
            this.provides = provides
            return this
        }

        fun setTiebreaker(tiebreaker: UUID): Builder {
            this.tiebreaker = tiebreaker
            return this
        }

        fun setHash(hash: ByteString): Builder {
            this.hashVal = hash
            return this
        }

        fun build(): ElectLeaderPacket {
            require((!(provides == null || tiebreaker == null)) || hashVal != null) { "both tiebreaker and provides must be set" }
            return ElectLeaderPacket(this)
        }
    }

    companion object {
        private fun builderFromIs(inputStream: InputStream) : Builder {
            val electleader = CRCProtobuf.parseFromCRC(ElectLeader.parser(), inputStream)
            val builder = Builder()
            return if (electleader.valCase.compareTo(ElectLeader.ValCase.VAL_BODY) == 0) {
                builder.setProvides(AdvertisePacket.valToProvides(electleader.valBody.provides))
                        .setTiebreaker(
                                UUID(
                                        electleader.valBody.tiebreakerVal.upper,
                                        electleader.valBody.tiebreakerVal.lower
                                )
                        );
            } else {
                builder.enableHashing()
                        .setHash(electleader.valHash)
            }
        }
        
        fun uuidToBytes(uuid: UUID?): ByteArray {
            val uuidBuffer = ByteBuffer.allocate(16)
            uuidBuffer.putLong(uuid!!.mostSignificantBits)
            uuidBuffer.putLong(uuid.leastSignificantBits)
            return uuidBuffer.array()
        }

        fun parseFrom(inputStream: InputStream): Single<ElectLeaderPacket> {
            return Single.fromCallable { ElectLeaderPacket(builderFromIs(inputStream)) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<ElectLeaderPacket> {
            val observer = InputStreamObserver(512) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>): Single<ElectLeaderPacket> {
            val observer = InputStreamFlowableSubscriber(512) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun newBuilder(): Builder {
            return Builder()
        }
    }
}