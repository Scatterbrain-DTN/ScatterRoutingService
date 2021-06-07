package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.p2p.WifiP2pInfo
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterbrainsdk.internal.HandshakeResult
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.getDefaultFileName
import net.ballmerlabs.uscatterbrain.db.getGlobalHashProto
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import java.util.*

/**
 * dagger2 interface for WifiDirectRadioModule
 */
interface WifiDirectRadioModule {
    fun connectToGroup(name: String, passphrase: String, timeout: Int): Single<WifiP2pInfo>
    fun bootstrapFromUpgrade(upgradeRequest: BootstrapRequest): Single<HandshakeResult>
    fun unregisterReceiver()
    fun registerReceiver()
    fun createGroup(): Single<WifiDirectBootstrapRequest>
    fun removeGroup(): Completable

    /**
     * Wrapper class combining BlockHeaderPacket, SequencePackets, and
     * a database entity
     *
     * Allows streaming messages from network directly into database/filestore
     */
    class BlockDataStream {
        val sequencePackets: Flowable<BlockSequencePacket>
        val headerPacket: BlockHeaderPacket
        val entity: ScatterMessage?
        private val sequenceCompletable = CompletableSubject.create()

        constructor(headerPacket: BlockHeaderPacket, sequencePackets: Flowable<BlockSequencePacket>) {
            this.sequencePackets = sequencePackets
                    .doOnComplete { sequenceCompletable.onComplete() }
                    .doOnError { e: Throwable? -> sequenceCompletable.onError(e!!) }
            this.headerPacket = headerPacket
            if (headerPacket.isEndOfStream) {
                entity = null
            } else {
                entity = ScatterMessage(
                        HashlessScatterMessage(
                                null,
                                null,
                                headerPacket.toFingerprint,
                                headerPacket.fromFingerprint,
                                headerPacket.application,
                                headerPacket.signature,
                                headerPacket.sessionID,
                                headerPacket.blockSize,
                                headerPacket.extension,
                                getDefaultFileName(headerPacket),
                                getGlobalHashProto(headerPacket.hashList),
                                headerPacket.userFilename,
                                headerPacket.mime,
                                headerPacket.sendDate,
                                Date().time
                        ),
                        HashlessScatterMessage.hash2hashsProto(headerPacket.hashList)
                )
            }
        }

        fun await(): Completable {
            return sequencePackets.ignoreElements()
        }

        val toDisk: Boolean
            get() = headerPacket.toDisk

        @JvmOverloads
        constructor(message: ScatterMessage, packetFlowable: Flowable<BlockSequencePacket>, end: Boolean = false, todisk: Boolean = true) {
            headerPacket = BlockHeaderPacket.newBuilder()
                    .setToFingerprint(message.message.to)
                    .setFromFingerprint(message.message.from)
                    .setApplication(message.message.application)
                    .setSig(message.message.sig)
                    .setToDisk(todisk)
                    .setSessionID(message.message.sessionid)
                    .setBlockSize(message.message.blocksize)
                    .setMime(message.message.mimeType)
                    .setExtension(message.message.extension)
                    .setHashes(HashlessScatterMessage.hashes2hashProto(message.messageHashes))
                    .setEndOfStream(end)
                    .build()

            entity = message
            sequencePackets = packetFlowable
                    .doOnComplete { sequenceCompletable.onComplete() }
                    .doOnError { e: Throwable? -> sequenceCompletable.onError(e!!) }
        }

        companion object {
            fun endOfStream(): BlockDataStream {
                return BlockDataStream(
                        BlockHeaderPacket.newBuilder().setEndOfStream(true).build(),
                        Flowable.empty()
                )
            }
        }
    }

    companion object {
        const val TAG = "WifiDirectRadioModule"
    }
}