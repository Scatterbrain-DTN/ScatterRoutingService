package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleConnection
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.network.proto.*
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket

import java.util.UUID

interface CachedLeConnection {
    val connection: BehaviorSubject<RxBleConnection>
    fun subscribeNotifs(): Completable
    fun awaitNotificationsSetup(): Completable
    fun subscribeConnection(rawConnection: Observable<RxBleConnection>)
    fun setOnDisconnect(callback: () -> Completable)
    fun sendForget(luid: UUID): Completable
    /**
     * reads a single advertise packet
     * @return single emitting advertise packet
     */
    fun readAdvertise(): Single<AdvertisePacket>

    /**
     * reads a single upgrade packet
     * @return single emitting upgrade packet
     */
    fun readUpgrade(): Single<UpgradePacket>

    /**
     * reads a single blockheader packet
     * @return single emitting blockheader packet
     */
    fun readBlockHeader(): Single<BlockHeaderPacket>

    /**
     * reads a single blocksequence packet
     * @return single emitting blocksequence packet
     */
    fun readBlockSequence(): Single<BlockSequencePacket>

    /**
     * reads a single declarehashes packet
     * @return single emitting delcarehashes packet
     */
    fun readDeclareHashes(): Single<DeclareHashesPacket>

    /**
     * reads a single electleader packet
     * @return single emitting electleader packet
     */
    fun readElectLeader(): Single<ElectLeaderPacket>

    /**
     * reads a single identity packet
     * @return single emitting identity packet
     */
    fun readIdentityPacket(): Single<IdentityPacket>

    /**
     * reads a single luid packet
     * @return single emitting luid packet
     */
    fun readLuid(): Single<LuidPacket>

    /**
     * reads a single ack packet
     * @return single emititng ack packet
     */
    fun readAck(): Single<AckPacket>

    fun disconnect()
}