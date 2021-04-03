package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.RoutingMetadata
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.util.*

/**
 * wrapper class for RoutingMetadata protobuf message
 */
class RoutingMetadataPacket : ScatterSerializable {
    private val routingMetadata: RoutingMetadata
    private val metadataMap = HashMap<UUID, ByteArray>()
    override var luid: UUID? = null
        private set

    private constructor(inputStream: InputStream) {
        routingMetadata = CRCProtobuf.parseFromCRC(RoutingMetadata.parser(), inputStream)
        addMap(routingMetadata.keyvalMap)
    }

    private constructor(builder: Builder) {
        routingMetadata = RoutingMetadata.newBuilder()
                .putAllKeyval(map)
                .setId(ScatterProto.UUID.newBuilder()
                        .setLower(builder.uuid!!.leastSignificantBits)
                        .setUpper(builder.uuid!!.mostSignificantBits))
                .setEndofstream(builder.empty)
                .build()
    }

    @Synchronized
    private fun addMap(`val`: Map<String, ByteString>) {
        metadataMap.clear()
        for ((key, value) in `val`) {
            metadataMap[UUID.fromString(key)] = value.toByteArray()
        }
    }

    @get:Synchronized
    private val map: Map<String, ByteString>
        get() {
            val result: MutableMap<String, ByteString> = HashMap()
            for ((key, value) in metadataMap) {
                result[key.toString()] = ByteString.copyFrom(value)
            }
            return result
        }

    val metadata: Map<UUID, ByteArray>
        get() = metadataMap

    val isEmpty: Boolean
        get() = routingMetadata.endofstream

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(routingMetadata, os)
                os.toByteArray()
            } catch (ignored: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(routingMetadata, os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_DECLARE_HASHES

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    class Builder {
        private val map: MutableMap<UUID, ByteArray> = HashMap()
        var empty = false
        var uuid: UUID? = null
        fun addMetadata(map: Map<UUID, ByteArray>?): Builder {
            this.map.putAll(map!!)
            return this
        }

        fun setEmpty(): Builder {
            empty = true
            return this
        }

        fun build(): RoutingMetadataPacket {
            require(!(uuid == null && !empty)) { "uuid must be set" }
            if (empty) {
                uuid = UUID(0, 0)
            }
            return RoutingMetadataPacket(this)
        }

    }

    companion object {
        fun newBuilder(): Builder {
            return Builder()
        }

        fun parseFrom(inputStream: InputStream): Single<RoutingMetadataPacket> {
            return Single.fromCallable { RoutingMetadataPacket(inputStream) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<RoutingMetadataPacket> {
            val observer = InputStreamObserver(4096) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>): Single<RoutingMetadataPacket> {
            val observer = InputStreamFlowableSubscriber(4096) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }
    }
}