package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.util.Pair
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
typealias ServerTransaction = (connection: CachedLeServerConnection) -> Single<TransactionResult<BootstrapRequest>>
typealias ClientTransaction = (conn: CachedLeConnection) -> Single<TransactionResult<BootstrapRequest>>

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
    val device: RxBleDevice,
    luid: UUID,
    val client: CachedLeConnection,
    val server: CachedLeServerConnection,
    val remoteLuid: UUID,
    val hashedSelf: UUID
) {
    private val LOG by scatterLog()
    val luidStage: LuidStage = LuidStage(luid, remoteLuid) //exchange hashed and unhashed luids
    val advertiseStage: AdvertiseStage = AdvertiseStage() //advertise router capabilities
    val votingStage: VotingStage = VotingStage(hashedSelf, remoteLuid) //determine if an upgrade takes place
    var upgradeStage: UpgradeStage? = null //possibly upgrade to new transport
    private val transactionMap =
        ConcurrentHashMap<String, Pair<ClientTransaction, ServerTransaction>>()
    private val stageChanges = BehaviorSubject.create<String>()
    var locked = BehaviorSubject.create<Boolean>()
    var stage: String = TransactionResult.STAGE_START
        set(value) {
            stageChanges.onNext(value)
            field = value
        }
    var role = ConnectionRole(
        role = BluetoothLEModule.Role.ROLE_UKE,
        luids = mutableMapOf(),
        band = FakeWifiP2pConfig.GROUP_OWNER_BAND_AUTO
        )
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
     * unlock this session if a transaction is complete
     */
    fun unlock() {
        if (locked.value!!) {
            locked.onNext(false)
        }
    }

    /**
     * get the server transaction for the current stage
     * @return server transaction
     */
    fun singleServer(): Single<ServerTransaction> {
        return Single.fromCallable { transactionMap[stage]!!.second }
            .doOnError { err: Throwable -> LOG.e("failed to get single server for stage $stage: $err") }
    }

    /**
     * get the client transaction for the current stage
     * @return client transaction
     */
    fun singleClient(): Single<ClientTransaction> {
        return Single.fromCallable { transactionMap[stage]!!.first }
            .doOnError { err: Throwable -> LOG.e("failed to get single client for stage $stage: $err") }
    }

    /**
     * Observe stage changes.
     * The next state will only be handled if unlock() is called by the state machine
     * @return observable emitting name of each stage and calling oncomplete on STAGE_EXIT
     */
    fun observeStage(): Observable<String> {
        return stageChanges
            .takeWhile { s -> s.compareTo(TransactionResult.STAGE_TERMINATE) != 0 }
    }

    /**
     * register an upgrade stage if we decide to upgrade
     * @param provides what transport to upgrade to
     */
    fun setUpgradeStage(provides: VotingResult) {
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

    init {
        locked.onNext(false)
    }

    companion object {
        const val INITIAL_STAGE = TransactionResult.STAGE_LUID
    }

}