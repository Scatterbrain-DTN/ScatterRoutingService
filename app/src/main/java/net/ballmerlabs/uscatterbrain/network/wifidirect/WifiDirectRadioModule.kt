package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.Flow

/**
 * dagger2 interface for WifiDirectRadioModule
 */
interface WifiDirectRadioModule {
    fun getBand(): Int

    fun addUke(uuid: UUID, bootstrap: UpgradePacket): Completable
    fun setUke(ukes: Map<UUID, UpgradePacket>): Completable
    fun removeUke(uuid: UUID)
    fun getUkes(): Map<UUID, UpgradePacket>

    fun bootstrapUke(band: Int, remoteLuid: UUID, selfLuid: UUID): Single<WifiDirectBootstrapRequest>
    fun bootstrapSeme(name: String, passphrase: String, band: Int, ownerPort: Int, self: UUID)

    fun safeShutdownGroup(): Completable

    fun awaitUke(): Observable<Pair<UUID, UpgradePacket>>

    fun getForceUke(): Boolean

    /**
     * Manually creates a wifi direct group, autogenerating the username/passphrase and
     * returning them as a BootstrapRequest
     * @return WifiDirectBootstrapRequest
     */
    fun createGroup(band: Int, remoteLuid: UUID, selfLuid: UUID): Flowable<WifiDirectBootstrapRequest>

    /**
     * Removes an existing wifi direct group if it exists
     * @return Completable
     */
    fun removeGroup(retries: Int = 10, delay: Int = 5): Completable

    /**
     * Ugly hack to determine if SoftAp or similar is hogging the wireless
     * adapter and blocking wifi direct
     *
     * @return Single emitting boolean, true if usable
     */
    fun wifiDirectIsUsable(): Single<Boolean>

    fun registerReceiver()

    fun unregisterReceiver()

    /**
     * Wrapper class combining BlockHeaderPacket, SequencePackets, and
     * a database entity
     *
     * Allows streaming messages from network directly into database/filestore
     *
     * @property headerPacket blockheader for this stream
     * @property sequencePacketsParam Flowable of sequence packets corresponding to the header packet
     * @property entity database entity for inserting this stream into the datastore, possibly autogenerated
     */
    class BlockDataStream(
        val headerPacket: BlockHeaderPacket,
        private val sequencePacketsParam: Flowable<BlockSequencePacket>,
        val entity: DbMessage?,
    ) {
        private val sequenceCompletable = CompletableSubject.create()
        val sequencePackets: Flowable<BlockSequencePacket> = sequencePacketsParam
            .doOnComplete { sequenceCompletable.onComplete() }
            .doOnError { e -> sequenceCompletable.onError(e) }

        constructor(
            headerPacket: BlockHeaderPacket,
            sequencePackets: Flowable<BlockSequencePacket>,
            filePath: File
        ) : this(
            entity = DbMessage.from(headerPacket, filePath),
            headerPacket = headerPacket,
            sequencePacketsParam = sequencePackets
        )

        fun await(): Completable {
            return sequenceCompletable
        }

        val toDisk: Boolean
            get() = headerPacket.isFile

        constructor(
            message: DbMessage,
            packetFlowable: Flowable<BlockSequencePacket>,
            todisk: Boolean = true
        ) : this(
            headerPacket = BlockHeaderPacket.newBuilder()
                .setToFingerprint(message.toFingerprint.firstOrNull())
                .setFromFingerprint(message.toFingerprint.firstOrNull())
                .setApplication(message.message.application)
                .setSig(message.message.sig)
                .setToDisk(todisk)
                .setSessionID(message.message.sessionid)
                .setMime(message.message.mimeType)
                .setExtension(message.message.extension)
                .setHashes(HashlessScatterMessage.hashes2hashProto(message.file.messageHashes))
                .setEndOfStream(false)
                .build(),
            entity = message,
            sequencePacketsParam = packetFlowable
        )

        companion object {
            fun endOfStream(): BlockDataStream {
                return BlockDataStream(
                    BlockHeaderPacket.newBuilder().setEndOfStream(true).build(),
                    Flowable.empty(),
                    File("/")
                )
            }
        }
    }
}