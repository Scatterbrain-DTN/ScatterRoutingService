package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto.Advertise
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.util.*

/**
 * Wrapper class for advertisepacket protocol buffer message.
 */
class AdvertisePacket private constructor(builder: Builder) : ScatterSerializable {
    private val mAdvertise: Advertise

    /**
     * Gets provides.
     *
     * @return the provides
     */
    val provides: List<Provides>?

    enum class Provides(val `val`: Int) {
        INVALID(-1), BLE(0), WIFIP2P(1);

    }

    override var luid: UUID? = null
        private set

    init {
        provides = builder.provides
        mAdvertise = Advertise.newBuilder()
                .addAllProvides(providesToValArray(provides))
                .build()

    }

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(mAdvertise, os)
                os.toByteArray()
            } catch (e: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(mAdvertise, os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_ADVERTISE

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    /**
     * builder for advertise packet
     */
    class Builder
    /**
     * Instantiates a new Builder.
     */
    {
        /**
         * Gets provides.
         *
         * @return the provides
         */
        var provides: List<Provides>? = null
            private set

        /**
         * Sets provides.
         *
         * @param provides scatterbrain provides enum
         * @return builder
         */
        fun setProvides(provides: List<Provides>?): Builder {
            this.provides = provides
            return this
        }

        /**
         * Build advertise packet.
         *
         * @return the advertise packet
         */
        fun build(): AdvertisePacket? {
            return if (provides == null) null else AdvertisePacket(this)
        }

    }

    companion object {
        private fun builderFromIs(inputStream: InputStream) : Builder {
            val advertise = CRCProtobuf.parseFromCRC(Advertise.parser(), inputStream)
            val provides = valToProvidesArray(advertise.getProvidesList())
            val builder = Builder()
                    .setProvides(provides)
            return builder
        }

        fun providesToValArray(provides: List<Provides>?): List<Int> {
            val res: MutableList<Int> = ArrayList()
            for (p in provides!!) {
                res.add(p.`val`)
            }
            return res
        }

        fun valToProvidesArray(vals: List<Int>): List<Provides> {
            val provides = ArrayList<Provides>()
            for (i in vals) {
                for (p in Provides.values()) {
                    if (p.`val` == i) {
                        provides.add(p)
                    }
                }
            }
            return provides
        }

        fun valToProvides(`val`: Int): Provides {
            for (p in Provides.values()) {
                if (p.`val` == `val`) {
                    return p
                }
            }
            return Provides.INVALID
        }

        fun providesToVal(provides: Provides?): Int {
            return provides!!.`val`
        }

        /**
         * Parse from advertise packet.
         *
         * @param is the is
         * @return the advertise packet
         */
        fun parseFrom(`is`: InputStream): Single<AdvertisePacket> {
            return Single.fromCallable { AdvertisePacket(builderFromIs(`is`)) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<AdvertisePacket> {
            val observer = InputStreamObserver(512) //TODO find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>): Single<AdvertisePacket> {
            val observer = InputStreamFlowableSubscriber(512) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        /**
         * New builder class
         *
         * @return the builder
         */
        fun newBuilder(): Builder {
            return Builder()
        }
    }
}