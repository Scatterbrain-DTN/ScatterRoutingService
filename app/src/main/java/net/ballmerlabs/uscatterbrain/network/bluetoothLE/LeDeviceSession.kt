package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothDevice
import android.util.Log
import android.util.Pair
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.TransactionResult
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
typealias ServerTransaction = (connection: CachedLEServerConnection) -> Single<Optional<BootstrapRequest>>
typealias ClientTransaction = (conn: CachedLEConnection) -> Single<TransactionResult<BootstrapRequest>>


class LeDeviceSession(val device: BluetoothDevice, luid: AtomicReference<UUID>) {
    val luidStage: LuidStage
    val advertiseStage: AdvertiseStage
    val votingStage: VotingStage
    var upgradeStage: UpgradeStage? = null
    private val transactionMap = ConcurrentHashMap<String, Pair<ClientTransaction, ServerTransaction>>()
    private val stageChanges = BehaviorSubject.create<String?>()
    val luidMap = ConcurrentHashMap<String, UUID>()
    var stage: String = TransactionResult.Companion.STAGE_START
    set(value) {
        stageChanges.onNext(value)
        field = value
    }
    var role = ConnectionRole.ROLE_UKE
    private var declareHashesPacket: DeclareHashesPacket? = DeclareHashesPacket.Companion.newBuilder().build()
    fun addStage(
            name: String,
            stage: ServerTransaction,
            transaction: ClientTransaction
    ) {
        transactionMap[name] = Pair(transaction, stage)
    }

    fun singleServer(): Single<ServerTransaction> {
        return Single.fromCallable { transactionMap[stage]!!.second }
                .doOnError { err: Throwable -> Log.e(TAG, "failed to get single server for stage $stage: $err") }
                .onErrorResumeNext(Single.never())
    }

    fun singleClient(): Single<ClientTransaction> {
        return Single.fromCallable { transactionMap[stage]!!.first }
                .doOnError { err: Throwable -> Log.e(TAG, "failed to get single client for stage $stage: $err") }
                .onErrorResumeNext(Single.never())
    }

    fun observeStage(): Observable<String?> {
        return stageChanges
                .takeWhile { s: String? -> s!!.compareTo(TransactionResult.Companion.STAGE_EXIT) != 0 }
                .delay(0, TimeUnit.SECONDS)
    }

    fun setUpgradeStage(provides: AdvertisePacket.Provides) {
        upgradeStage = UpgradeStage(provides)
    }

    fun setDeclareHashesPacket(packet: DeclareHashesPacket?) {
        declareHashesPacket = packet
    }

    val declareHashes: Single<DeclareHashesPacket?>
        get() = Single.just(declareHashesPacket)

    val server: ServerTransaction
        get() = transactionMap[stage]!!.second

    val client: ClientTransaction
        get() = transactionMap[stage]!!.first

    companion object {
        const val TAG = "LeDeviceSession"
    }

    init {
        luidStage = LuidStage(device, luid)
        advertiseStage = AdvertiseStage()
        votingStage = VotingStage(device)
    }
}