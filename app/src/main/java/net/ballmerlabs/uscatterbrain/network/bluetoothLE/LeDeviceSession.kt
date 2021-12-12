package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothDevice
import android.util.Log
import android.util.Pair
import com.polidea.rxandroidble2.internal.util.GattServerTransaction
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket
import net.ballmerlabs.uscatterbrain.network.LuidPacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/*
 * server and client transactions are represented as functions taking a GATT connection
 * as a parameter and returning some form of an optional bootstrap request.
 *
 * Client transaction return a transaction result containing a TransactionResult object
 * potentially containing a bootstrap request
 *
 * Server transaction return an optional bootstrap request
 *
 * if the bootstrap request is present, Scatterbrain will attempt to handle data transfer on
 * a new transport
 */
typealias ServerTransaction = (connection: CachedLEServerConnection) -> Single<OptionalBootstrap<BootstrapRequest>>
typealias ClientTransaction = (conn: CachedLEConnection) -> Single<TransactionResult<BootstrapRequest>>

/**
 * LeDeviceSession holds the state for the finite state machine managing a scatterbrain connection
 * over bluetooth LE between two devices. Each state in the FSM is referred to as a "stage"
 * and gets its own member class
 * @property device raw BluetoothDevice
 * @property client gatt client wrapper
 * @property server gatt server wrapper
 * @property remoteLuid local identifier of remote device
 */
class LeDeviceSession(
        val device: BluetoothDevice, 
        luid: UUID,
        val client: CachedLEConnection,
        val server: CachedLEServerConnection,
        val remoteLuid: UUID
) {
    val luidStage: LuidStage = LuidStage(luid, remoteLuid) //exchange hashed and unhashed luids
    val advertiseStage: AdvertiseStage = AdvertiseStage() //advertise router capabilities
    val votingStage: VotingStage = VotingStage() //determine if an upgrade takes place
    var upgradeStage: UpgradeStage? = null //possibly upgrade to new transport
    private val transactionMap = ConcurrentHashMap<String, Pair<ClientTransaction, ServerTransaction>>()
    private val stageChanges = BehaviorSubject.create<String>()
    var locked = false
    val luidMap = ConcurrentHashMap<String, UUID>()
    var stage: String = TransactionResult.STAGE_START
    set(value) {
        stageChanges.onNext(value)
        field = value
    }
    var role = ConnectionRole.ROLE_UKE
    private var declareHashesPacket: DeclareHashesPacket? = DeclareHashesPacket.newBuilder().build()

    /**
     * register a new state/stage in the FSM
     * @param name unique name for this stage
     * @param serverTransaction function for behavior of GATT server
     * @param clientTransaction function for behavior of GATT client
     */
    fun addStage(
            name: String,
            serverTransaction: ServerTransaction,
            clientTransaction: ClientTransaction
    ) {
        transactionMap[name] = Pair(clientTransaction, serverTransaction)
    }

    /**
     * lock this session if a transaction is in progress
     */
    fun lock() = apply {
        locked = true
    }

    /**
     * unlock this session if a transaction is complete
     */
    fun unlock() = apply {
        locked = false
    }

    /**
     * get the server transaction for the current stage
     * @return server transaction
     */
    fun singleServer(): Single<ServerTransaction> {
        return Single.fromCallable { transactionMap[stage]!!.second }
                .doOnError { err: Throwable -> Log.e(TAG, "failed to get single server for stage $stage: $err") }
    }

    /**
     * get the client transaction for the current stage
     * @return client transaction
     */
    fun singleClient(): Single<ClientTransaction> {
        return Single.fromCallable { transactionMap[stage]!!.first }
                .doOnError { err: Throwable -> Log.e(TAG, "failed to get single client for stage $stage: $err") }
    }

    /**
     * Observe stage changes.
     * @return observable emitting name of each stage and calling oncomplete on STAGE_EXIT
     */
    fun observeStage(): Observable<String> {
        return stageChanges
                .takeWhile { s -> s.compareTo(TransactionResult.STAGE_TERMINATE) != 0 }
                .filter {s -> s.compareTo(TransactionResult.STAGE_SUSPEND) != 0 }
                .delay(0, TimeUnit.SECONDS)
    }

    /**
     * register an upgrade stage if we decide to upgrade
     * @param provides what transport to upgrade to
     */
    fun setUpgradeStage(provides: AdvertisePacket.Provides) {
        upgradeStage = UpgradeStage(provides)
    }

    /**
     * register a delcarehashes packet to query datastore with
     * @param packet declare hashes packet
     */
    fun setDeclareHashesPacket(packet: DeclareHashesPacket?) {
        declareHashesPacket = packet
    }

    val declareHashes: Single<DeclareHashesPacket?>
        get() = Single.just(declareHashesPacket)

    
    interface Stage {
        fun reset()
    }
    
    companion object {
        const val TAG = "LeDeviceSession"
        const val INITIAL_STAGE = TransactionResult.STAGE_LUID
    }

}