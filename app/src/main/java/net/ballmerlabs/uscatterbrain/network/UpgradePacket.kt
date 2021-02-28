package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto.Upgrade
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.util.*

/**
 * Wrapper class for protocol buffer upgrade message
 */
class UpgradePacket : ScatterSerializable {
    private val mUpgrade: Upgrade?

    /**
     * Gets session id.
     *
     * @return the session id
     */
    val sessionID: Int

    /**
     * gets the metadata map
     */
    val metadata: Map<String, String>?

    /**
     * Gets provides.
     *
     * @return the provies
     */
    val provides: AdvertisePacket.Provides?
    override var luid: UUID? = null
        private set

    private constructor(builder: Builder) {
        provides = builder.provides
        sessionID = builder.sessionID
        metadata = builder.metadata
        mUpgrade = Upgrade.newBuilder()
                .setProvides(AdvertisePacket.Companion.providesToVal(provides))
                .setSessionid(sessionID)
                .putAllMetadata(metadata)
                .build()
    }

    private constructor(`is`: InputStream) {
        mUpgrade = CRCProtobuf.parseFromCRC(Upgrade.parser(), `is`)
        sessionID = mUpgrade.getSessionid()
        provides = AdvertisePacket.Companion.valToProvides(mUpgrade.getProvides())
        metadata = mUpgrade.getMetadataMap()
    }

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(mUpgrade, os)
                os.toByteArray()
            } catch (e: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(mUpgrade, os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_UPGRADE

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    /**
     * The type Builder.
     */
    class Builder {
        /**
         * Gets session id.
         *
         * @return the session id
         */
        var sessionID = 0
            private set

        /**
         * Gets provies.
         *
         * @return provides
         */
        var provides: AdvertisePacket.Provides? = null
            private set
        var metadata: Map<String, String>? = null

        /**
         * Sets session id.
         *
         * @param sessionID the session id
         * @return builder
         */
        fun setSessionID(sessionID: Int): Builder {
            this.sessionID = sessionID
            return this
        }

        /**
         * Sets provides.
         *
         * @param provides the provides
         * @return builder
         */
        fun setProvides(provides: AdvertisePacket.Provides?): Builder {
            this.provides = provides
            return this
        }

        fun setMetadata(metadata: Map<String, String>?): Builder {
            this.metadata = metadata
            return this
        }

        /**
         * Build upgrade packet.
         *
         * @return the upgrade packet
         */
        fun build(): UpgradePacket? {
            if (provides == null) return null
            if (metadata == null) {
                metadata = HashMap()
            }
            return UpgradePacket(this)
        }
    }

    companion object {
        /**
         * Parse from upgrade packet.
         *
         * @param is the is
         * @return the upgrade packet
         */
        fun parseFrom(`is`: InputStream): Single<UpgradePacket> {
            return Single.fromCallable { UpgradePacket(`is`) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<UpgradePacket> {
            val observer = InputStreamObserver(4096) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>): Single<UpgradePacket> {
            val observer = InputStreamFlowableSubscriber(4098) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        /**
         * Constructs a new builder class.
         *
         * @return the builder
         */
        fun newBuilder(): Builder {
            return Builder()
        }
    }
}