package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.p2p.WifiP2pInfo
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest

interface WifiDirectRadioModule {
    fun connectToGroup(name: String, passphrase: String, timeout: Int): Single<WifiP2pInfo>
    fun bootstrapFromUpgrade(upgradeRequest: BootstrapRequest): Single<HandshakeResult>
    fun unregisterReceiver()
    fun registerReceiver()
    class BlockDataStream {
        val sequencePackets: Flowable<BlockSequencePacket>
        val headerPacket: BlockHeaderPacket
        val entity: ScatterMessage
        private val sequenceCompletable = CompletableSubject.create()

        constructor(headerPacket: BlockHeaderPacket, sequencePackets: Flowable<BlockSequencePacket>) {
            this.sequencePackets = sequencePackets
                    .doOnComplete { sequenceCompletable.onComplete() }
                    .doOnError { e: Throwable? -> sequenceCompletable.onError(e!!) }
            this.headerPacket = headerPacket
            entity = ScatterMessage()
            entity.message = HashlessScatterMessage()
            entity.message!!.to = headerPacket.toFingerprint.toByteArray()
            entity.message!!.from = headerPacket.fromFingerprint.toByteArray()
            entity.message!!.application = headerPacket.application
            entity.message!!.sig = headerPacket.signature
            entity.message!!.sessionid = headerPacket.sessionID
            entity.message!!.blocksize = headerPacket.blockSize
            entity.message!!.mimeType = headerPacket.mime
            entity.message!!.extension = headerPacket.getExtension()
            entity.message!!.globalhash = ScatterbrainDatastore.getGlobalHash(headerPacket.hashList)
            entity.messageHashes = HashlessScatterMessage.hash2hashs(headerPacket.hashList)
        }

        fun await(): Completable {
            return sequencePackets.ignoreElements()
        }

        val toDisk: Boolean
            get() = headerPacket.toDisk

        @JvmOverloads
        constructor(message: ScatterMessage, packetFlowable: Flowable<BlockSequencePacket>, end: Boolean = false, todisk: Boolean = true) {
            val builder: BlockHeaderPacket.Builder = BlockHeaderPacket.newBuilder()
                    .setToFingerprint(message.message!!.to)
                    .setFromFingerprint(message.message!!.from)
                    .setApplication(message.message!!.application!!)
                    .setSig(message.message!!.sig)
                    .setToDisk(todisk)
                    .setSessionID(message.message!!.sessionid)
                    .setBlockSize(message.message!!.blocksize)
                    .setMime(message.message!!.mimeType)
                    .setExtension(message.message!!.extension!!)
                    .setHashes(HashlessScatterMessage.Companion.hashes2hash(message.messageHashes!!))
            if (end) {
                builder.setEndOfStream()
            }
            headerPacket = builder.build()
            entity = message
            sequencePackets = packetFlowable
                    .doOnComplete { sequenceCompletable.onComplete() }
                    .doOnError { e: Throwable? -> sequenceCompletable.onError(e!!) }
            checkNotNull(headerPacket) { "header packet was null" }
        }

        fun awaitSequencePackets(): Completable {
            return sequenceCompletable
        }

    }

    companion object {
        const val TAG = "WifiDirectRadioModule"
    }
}