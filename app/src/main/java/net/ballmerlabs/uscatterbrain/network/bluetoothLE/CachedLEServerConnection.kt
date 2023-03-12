package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import androidx.room.util.convertByteToUUID
import com.google.protobuf.MessageLite
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
import io.reactivex.subjects.Subject
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.Companion.GATT_SIZE
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.OwnedCharacteristic
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class QueueItem(
    val packet: ScatterSerializable<out MessageLite>,
    val cookie: Int,
    val luid: UUID
)

data class QueueEvent(
    val channel: OwnedCharacteristic,
    val device: RxBleDevice,
    val luid: UUID
)

data class QueueHandle(
    val subject: Subject<QueueItem>,
    val disposable: Disposable
)

/**
 * Wraps an RxBleServerConnection and provides channel locking and a convenient interface to
 * serialize protobuf messages via indications.
 * @property connection raw connection object being wrapped by this class
 */
class CachedLEServerConnection(
    val connection: GattServerConnection,
    private val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic>,
    private val scheduler: Scheduler,
    private val ioScheduler: Scheduler,
    private val firebaseWrapper: FirebaseWrapper
) {
    val luid: UUID? = null
    private val LOG by scatterLog()
    private val disposable = CompositeDisposable()
    private val packetQueue = ConcurrentHashMap<UUID, QueueHandle>()
    private val errorRelay = PublishRelay.create<Throwable>() //TODO: handle errors
    private val cookies = AtomicReference(0)
    private val cookieCompleteRelay = PublishRelay.create<Int>()
    private val serverReady = PublishSubject.create<UUID>()

    private val serverObs = connection.getEvents(
    )
        .subscribeOn(ioScheduler)
        .doOnNext { v-> LOG.v("semaphor read ${v.operation}") }
        .flatMapMaybe { req ->
            when (req.operation) {
                GattServerConnection.Operation.CHARACTERISTIC_READ -> {
                    when (req.uuid) {
                        BluetoothLERadioModuleImpl.UUID_SEMAPHOR -> {
                            selectCharacteristic().flatMapCompletable { characteristic ->
                                req.sendReply(
                                    BluetoothLERadioModuleImpl.uuid2bytes(
                                        characteristic.uuid
                                    ), BluetoothGatt.GATT_SUCCESS
                                )
                            }.andThen(Maybe.empty())
                        }
                        else -> Maybe.empty()

                    }
                }
                GattServerConnection.Operation.CHARACTERISTIC_WRITE -> {
                    req.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                        .andThen(Maybe.defer {
                            for (lock in channels.values) {
                                LOG.e("server got notf uuid ${lock.uuid} ${req.uuid} ${lock.isLocked()}")
                                if (lock.isLocked() && lock.uuid == req.uuid) {
                                    LOG.e("returning QueueEvent")
                                    val luid =
                                        BluetoothLERadioModuleImpl.bytes2uuid(req.value)!!
                                    return@defer Maybe.just(QueueEvent(
                                        luid = luid,
                                        channel = lock.asUnlocked(),
                                        device = req.remoteDevice
                                    ))
                                }
                            }
                            LOG.e("failed to notify, not locked")
                            Maybe.empty()
                        })

                }
                else -> Maybe.empty()
            }
        }


    private fun subscribeLuid(luid: UUID): Single<QueueHandle> {
        return Single.create { sub ->
            val h = packetQueue[luid]
            if (h != null) {
                sub.onSuccess(h)
            } else {
                val subject = PublishSubject.create<QueueItem>()
                val disp = serverObs.zipWith(subject) { event, item ->
                    LOG.e("queue pop: ${item.cookie}")
                    if (event.luid == item.luid) {
                        connection.setupNotifications(
                            event.channel.uuid,
                            item.packet.writeToStream(
                                GATT_SIZE,
                                scheduler,
                            ),
                            event.device
                        )
                            .doOnComplete { LOG.e("notifications complete for ${item.packet}") }
                            .doFinally {
                                if (cookieCompleteRelay.hasObservers()) {
                                    cookieCompleteRelay.accept(item.cookie)
                                }
                                event.channel.release()
                            }
                    } else {
                        Completable.complete()
                    }
                }.flatMapCompletable { c -> c }
                    .subscribeOn(ioScheduler)
                    .doOnSubscribe { disp ->
                        val handle = QueueHandle(
                            subject = subject,
                            disposable = disp
                        )
                        packetQueue[luid] = handle
                        sub.onSuccess(handle)
                    }
                    .subscribe(
                        { LOG.v("luid server handler $luid completed") },
                        { err -> LOG.e("luid server handler error $err") }
                    )
            }
        }
    }

    private fun getCookie(): Int {
        return cookies.getAndUpdate { v ->
            Math.floorMod(v + 1, Int.MAX_VALUE)
        }
    }


    private fun registerLuid(luid: UUID): Single<QueueHandle> {
        LOG.e("registerting luid $luid")
        return subscribeLuid(luid).map { c->
            packetQueue.putIfAbsent(luid, c)
            c
        }
    }


    /**
     * when a client reads from the semaphor characteristic, we need to
     * find an unlocked channel and return its uuid
     *
     * @return single emitting characteristic selected
     */
    private fun selectCharacteristic(): Single<OwnedCharacteristic> {
        return Single.defer {
            for (char in channels.values) {
                val lock = char.lock()
                if (lock != null) {
                    return@defer Single.just(lock)
                }
            }

            Single.error(IllegalStateException("no characteristics"))
        }
    }

    /**
     * Send a scatterbrain message to the connected client.
     * @param packet ScatterSerializable message to send
     * @return completable
     */
    fun <T : MessageLite> serverNotify(
        packet: ScatterSerializable<T>,
        luid: UUID
    ): Completable {
        return registerLuid(luid).flatMapCompletable { handle ->
            val cookie = getCookie()

            cookieCompleteRelay.takeUntil { v -> v == cookie }
                .ignoreElements()
                .timeout(30, TimeUnit.SECONDS)
                .doOnSubscribe {
                    LOG.e("serverNotify acccepted ${packet.type}")
                    handle.subject.onNext(
                        QueueItem(
                            packet = packet,
                            cookie = cookie,
                            luid = luid
                        )
                    )
                }
                .doOnComplete { LOG.v("serverNotify COMPLETED for ${packet.type} cookie $cookie") }
        }

    }

    /**
     * dispose this connected
     */
    fun dispose() {
        LOG.e("CachedLEServerConnection disposed")
        connection.dispose()
        disposable.dispose()
    }

    /**
     * return true if this connection is disposed
     * @return is disposed
     */
    fun isDisposed(): Boolean {
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
    }
}