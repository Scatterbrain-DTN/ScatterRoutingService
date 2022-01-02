package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.p2p.WifiP2pInfo
import android.util.Base64
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.IdentityId
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.getDefaultFileName
import net.ballmerlabs.uscatterbrain.db.getGlobalHash
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import java.util.*

/**
 * dagger2 interface for WifiDirectRadioModule
 */
interface WifiDirectRadioModule {
    /**
     * Connects to an existing wifi direct group manually
     * @param name group name. MUST start with DIRECT-*
     * @param passphrase group PSK. minimum 8 characters
     * @param timeout emits error from single if no connection is established within this many seconds
     * @return single emitting WifiP2pInfo if connection is successful, called onError if failed or timed out
     */
    fun connectToGroup(name: String, passphrase: String, timeout: Int): Single<WifiP2pInfo>

    /**
     * performs an automatic handshake with a peer specified by a WifiDirectBootstrapRequest object
     * from another transport module.
     * @param upgradeRequest bootstrap request generated with WifiDirectBootstrapRequest
     * @return Single emitting handshake result with transaction stats
     */
    fun bootstrapFromUpgrade(upgradeRequest: BootstrapRequest): Single<HandshakeResult>

    /**
     * unregisters the wifi direct broadcast receiver
     */
    fun unregisterReceiver()

    /**
     * registers the wifi direct broadcast receiver
     */
    fun registerReceiver()

    /**
     * Manually creates a wifi direct group, autogenerating the username/passphrase and
     * returning them as a BootstrapRequest
     * @return WifiDirectBootstrapRequest
     */
    fun createGroup(): Single<WifiDirectBootstrapRequest>

    /**
     * Removes an existing wifi direct group if it exists
     * @return Completable
     */
    fun removeGroup(): Completable

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
            val entity: ScatterMessage?
    ) {
        private val sequenceCompletable = CompletableSubject.create()
        val sequencePackets: Flowable<BlockSequencePacket> = sequencePacketsParam
               .doOnComplete { sequenceCompletable.onComplete() }
               .doOnError { e -> sequenceCompletable.onError(e) }

        constructor(headerPacket: BlockHeaderPacket, sequencePackets: Flowable<BlockSequencePacket>) : this(
                entity = if (headerPacket.isEndOfStream) null else ScatterMessage(
                        HashlessScatterMessage(
                                null,
                                headerPacket.application,
                                headerPacket.signature,
                                headerPacket.sessionID,
                                headerPacket.extension,
                                getDefaultFileName(headerPacket),
                                Base64.encodeToString(getGlobalHash(headerPacket.hashList), Base64.DEFAULT),
                                headerPacket.userFilename,
                                headerPacket.mime,
                                headerPacket.sendDate,
                                Date().time,
                                fileSize = -1,
                                packageName = ""
                        ),
                        HashlessScatterMessage.hash2hashs(headerPacket.hashList),
                        headerPacket.toFingerprint.map { u -> IdentityId(u) },
                        headerPacket.fromFingerprint.map { u -> IdentityId(u) }
                ),
                headerPacket = headerPacket,
                sequencePacketsParam = sequencePackets
        )

        fun await(): Completable {
            return sequenceCompletable
        }

        val toDisk: Boolean
            get() = headerPacket.isFile

        constructor(message: ScatterMessage, packetFlowable: Flowable<BlockSequencePacket>, todisk: Boolean = true): this(
                headerPacket = BlockHeaderPacket.newBuilder()
                        .setToFingerprint(message.toFingerprint.firstOrNull())
                        .setFromFingerprint(message.toFingerprint.firstOrNull())
                        .setApplication(message.message.application)
                        .setSig(message.message.sig)
                        .setToDisk(todisk)
                        .setSessionID(message.message.sessionid)
                        .setMime(message.message.mimeType)
                        .setExtension(message.message.extension)
                        .setHashes(HashlessScatterMessage.hashes2hashProto(message.messageHashes))
                        .setEndOfStream(false)
                        .build(),
                entity = message,
                sequencePacketsParam = packetFlowable
        )

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