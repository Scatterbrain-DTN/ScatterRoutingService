package net.ballmerlabs.uscatterbrain.scheduler

import io.reactivex.Completable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.network.proto.IdentityPacket
import java.util.UUID

/**
 * dagger2 interface for ScatterbrainScheduler
 */
interface ScatterbrainScheduler {
    fun start()
    fun stop(): Boolean
    fun pauseScan()
    fun unpauseScan()
    fun broadcastTransactionResult(transactionStats: HandshakeResult): Completable
    fun acquireWakelock()
    fun releaseWakeLock()
    fun authorizeDesktop(fingerprint: ByteArray, authorize: Boolean): Completable
    fun startDesktopServer(name: String): Completable
    fun confirmIdentityImport(handle: UUID, identity: UUID): Completable
    fun stopDesktopServer()
    fun broadcastIdentities(identities: List<IdentityPacket>): Completable
    fun broadcastMessages(messages: List<DbMessage>): Completable
    val isDiscovering: Boolean
    val isPassive: Boolean
}