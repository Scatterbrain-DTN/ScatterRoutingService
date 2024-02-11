package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import com.google.protobuf.MessageLite
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.OwnedCharacteristic
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named

data class QueueItem(
    val packet: ScatterSerializable<out MessageLite>,
    val cookie: Int,
    val luid: UUID
)

data class SessionHandle(
    val disposable: Disposable,
    val subject: BehaviorSubject<QueueItem>,
    val characteristic: OwnedCharacteristic
)

/**
 * Wraps an RxBleServerConnection and provides channel locking and a convenient interface to
 * serialize protobuf messages via indications.
 * @property connection raw connection object being wrapped by this class
 */
@GattServerConnectionScope
class CachedLEServerConnectionImpl @Inject constructor (
    override val connection: GattServerConnection,
    private val state: LeState,
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val scheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.GLOBAL_IO) private val ioScheduler: Scheduler,
    private val firebaseWrapper: FirebaseWrapper,
    private val advertiser: Advertiser
) : CachedLeServerConnection {
    val luid: UUID? = null
    private val LOG by scatterLog()
    private val disposable = CompositeDisposable()
    private val packetQueue = ConcurrentHashMap<UUID, SessionHandle>()
    private val cookies = AtomicReference(0)
    private val cookieCompleteRelay = PublishRelay.create<Int>()
    private val luidRegisteredSubject =
        PublishSubject.create<Pair<UUID, BehaviorSubject<QueueItem>>>()

    private fun getCookie(): Int {
        return cookies.getAndUpdate { v ->
            Math.floorMod(v + 1, Int.MAX_VALUE)
        }
    }

    private fun awaitLuidRegistered(luid: UUID): Single<BehaviorSubject<QueueItem>> {
        return Single.defer {
            val item = packetQueue[luid]?.subject
            LOG.v("awaiting luid registration for luid $luid $item")
            if (item != null) {
                Single.just(item)
            } else {
                luidRegisteredSubject.takeUntil { v -> v.first == luid }
                    .map { v -> v.second }
                    .firstOrError()
            }
        }
    }

    override fun unlockLuid(luid: UUID) {
        LOG.e("server unlock luid $luid")
        val d = packetQueue.remove(luid)
        d?.subject?.onComplete()
    }

    private fun registerLuid(
        luid: UUID,
        device: RxBleDevice,
        characteristic: OwnedCharacteristic
    ): BehaviorSubject<QueueItem> {
        LOG.e("registering luid $luid ${characteristic.uuid}")
        val q = packetQueue.compute(luid) { k, v ->
            when (v) {
                null -> {
                    val subject = BehaviorSubject.create<QueueItem>()
                    val notif = connection.setupNotifications(
                        characteristic.uuid,
                        subject.toFlowable(BackpressureStrategy.BUFFER)
                            .flatMap { item ->
                                if (!characteristic.isLocked()) {
                                    Flowable.error(IllegalStateException("characteristic not owned"))
                                } else {
                                    val mtu = connection.getMtu(device.macAddress) - 3
                                    LOG.e("packet! ${item.packet.type} MTU: $mtu")
                                    item.packet.writeToStream(mtu, scheduler)
                                        .doFinally {
                                            LOG.e("packet write complete for ${item.packet.type} ")
                                            cookieCompleteRelay.accept(item.cookie)
                                        }
                                }.doOnCancel { cookieCompleteRelay.accept(item.cookie) }
                            }
                            .doOnError { err -> LOG.e("server notification error for $luid $err") }
                            .doFinally {
                                characteristic.release()
                            },
                        device
                    )
                        .doFinally {
                            LOG.w("releasing characteristic ${characteristic.uuid}")
                            characteristic.release()
                        }
                        .doOnDispose {
                            characteristic.release()
                        }
                        .subscribe(
                            { LOG.e("notification for $luid completed") },
                            { err -> LOG.e("notification for $luid error $err") }
                        )
                    val sub = SessionHandle(
                        subject = subject,
                        disposable = notif,
                        characteristic = characteristic
                    )
                    luidRegisteredSubject.onNext(Pair(luid, sub.subject))
                    sub
                }

                else -> v
            }

        }
        return q!!.subject
    }

    /**
     * when a client reads from the semaphor characteristic, we need to
     * find an unlocked channel and return its uuid
     *
     * @return single emitting characteristic selected
     */
    private fun selectCharacteristic(): Single<OwnedCharacteristic> {
        return Single.defer {
            for (char in state.channels.values) {
                val lock = char.lock()
                if (lock != null) {
                    return@defer Single.just(lock)
                }
            }

            Single.error(IllegalStateException("no characteristics"))
        }.doOnSuccess { v -> LOG.w("server selected channel ${v.uuid} for $luid") }
    }

    /**
     * Send a scatterbrain message to the connected client.
     * @param packet ScatterSerializable message to send
     * @return completable
     */
    override fun <T : MessageLite> serverNotify(
        packet: ScatterSerializable<T>,
        luid: UUID,
        remoteDevice: RxBleDevice
    ): Completable {
        return awaitLuidRegistered(luid).flatMapCompletable { subj ->
            LOG.e("serverNotify accepted ${packet.type}")
            LOG.e("me = ${advertiser.getHashLuid()} remote $luid")
            val cookie = getCookie()
            cookieCompleteRelay.takeUntil { v -> v == cookie }
                .ignoreElements()
                .mergeWith(Completable.fromAction {
                    subj.onNext(
                        QueueItem(
                            packet = packet,
                            cookie = cookie,
                            luid = luid
                        )
                    )
                })
                .timeout(36, TimeUnit.SECONDS, ioScheduler)
                .doOnComplete { LOG.v("serverNotify COMPLETED for ${packet.type} cookie $cookie") }
        }

    }

    /**
     * dispose this connected
     */
    override fun dispose() {
        LOG.e("CachedLEServerConnection disposed")
        connection.dispose()
        disposable.dispose()
    }

    /**
     * return true if this connection is disposed
     * @return is disposed
     */
    override fun isDisposed(): Boolean {
        return disposable.isDisposed
    }

    init {
        LOG.e("server connection init")
        /*
         * When a client reads from the semaphor characteristic, take the following steps
         * 
         * 1. select an unlocked channel and return its UUID to the client
         * 2. dequeue a packet, waiting for one if needed
         * 3. wait for the client to read from the unlocked channel
         * 4. serialize the packet over GATT indications to the client
         *
         * It should be noted that while there may be more then one packet waiting in the queue,
         * the adapter can only service one packet per channel
         */
        val d =
            connection.getEvents()
                .observeOn(ioScheduler)
                .flatMapCompletable { req ->
                    when (req.operation) {
                        GattServerConnection.Operation.CHARACTERISTIC_READ -> {
                            val res = when (req.uuid) {
                                BluetoothLERadioModuleImpl.UUID_SEMAPHOR -> {
                                    selectCharacteristic()
                                        .flatMapCompletable { characteristic ->

                                            LOG.e("starting characteristic lock for ${req.remoteDevice.macAddress}")
                                            req.sendReply(
                                                BluetoothLERadioModuleImpl.uuid2bytes(
                                                    characteristic.uuid
                                                ), BluetoothGatt.GATT_SUCCESS
                                            )
                                                .andThen(connection.getEvents()
                                                    .filter { c ->
                                                        c.operation == GattServerConnection.Operation.CHARACTERISTIC_WRITE
                                                                && c.characteristic.uuid == characteristic.uuid
                                                    }.firstOrError()
                                                    .flatMapCompletable { trans ->
                                                        if (state.channels.containsKey(trans.uuid)) {

                                                                Completable.defer {
                                                                    LOG.e("finalizing characteristic lock for ${trans.remoteDevice.macAddress}")
                                                                    val luid =
                                                                        BluetoothLERadioModuleImpl.bytes2uuid(
                                                                            trans.value
                                                                        )!!
                                                                    LOG.e("server LOCKED characteristic $luid ${characteristic.uuid}")
                                                                    registerLuid(
                                                                        luid,
                                                                        trans.remoteDevice,
                                                                        characteristic
                                                                    )
                                                                    Completable.complete()
                                                                }.andThen(
                                                                    trans.sendReply(
                                                                        byteArrayOf(),
                                                                        BluetoothGatt.GATT_SUCCESS
                                                                    )
                                                                )
                                                        } else {
                                                            trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_FAILURE)
                                                        }
                                                    }
                                                )
                                        }
                                }

                                else -> Completable.complete()

                            }
                            res
                        }

                        else -> Completable.complete()
                    }.onErrorComplete()
                }
                .repeat()
                .retry()
                .subscribe(
                    { LOG.e("server handler prematurely completed. GHAA") },
                    { err ->
                        LOG.e("server handler ended with error $err")
                        err.printStackTrace()
                    }
                )

        val d2 = luidRegisteredSubject.subscribe(
            {},
            { err -> LOG.w("luid registered subject failed $err") }
        )
        disposable.add(d)
        disposable.add(d2)
    }
}