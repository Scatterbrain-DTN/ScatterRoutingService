package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import androidx.room.util.convertByteToUUID
import com.google.protobuf.MessageLite
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
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
    private val packetQueue = ConcurrentHashMap<UUID, LinkedBlockingQueue<QueueItem>>()
    private val errorRelay = PublishRelay.create<Throwable>() //TODO: handle errors
    private val cookies = AtomicReference(0)
    private val cookieCompleteRelay = PublishRelay.create<Int>()
    private val serverReady = PublishSubject.create<UUID>()

    private fun getCookie(): Int {
        return cookies.getAndUpdate { v ->
            Math.floorMod(v + 1, Int.MAX_VALUE)
        }
    }


    private fun registerLuid(luid: UUID): LinkedBlockingQueue<QueueItem> {
        LOG.e("registerting luid $luid")
        val q = packetQueue.compute(luid) { k, v ->
            when (v) {
                null -> LinkedBlockingQueue()
                else -> v
            }

        }
        serverReady.onNext(luid)
        return q!!
    }

    private fun awaitServerReady(luid: UUID): Completable {
        return Completable.defer {
            if (packetQueue.contains(luid)) {
                Completable.complete()
            } else {
                serverReady.takeUntil { p -> p == luid }.ignoreElements()
            }
        }
            .doOnComplete { LOG.e("server ready") }
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
        return Completable.defer {
            LOG.e("serverNotify acccepted ${packet.type}")
            val cookie = getCookie()

            cookieCompleteRelay.takeUntil { v -> v == cookie }
                .ignoreElements()
                .timeout(30, TimeUnit.SECONDS)
                .doOnSubscribe {
                    val queue = registerLuid(luid)
                    queue.put(
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

    private fun handleQueueItem(
        luid: UUID,
        characteristic: UUID,
        device: RxBleDevice
    ): Completable {
        return Single.fromCallable {
            registerLuid(luid).poll(30, TimeUnit.SECONDS)
        }.subscribeOn(ioScheduler)
            .flatMapCompletable { packet ->
                LOG.e("serverconnection handling luid $luid")
                LOG.e("packet ${packet.luid} ${packet.packet.type}")
                connection.setupNotifications(
                    characteristic,
                    packet.packet.writeToStream(
                        GATT_SIZE,
                        ioScheduler
                    ),
                    device
                )
                    .doOnComplete { LOG.e("notifications complete for ${packet.luid}") }
                    .doFinally {
                        if (cookieCompleteRelay.hasObservers()) {
                            cookieCompleteRelay.accept(packet.cookie)
                        }
                    }
            }
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
            connection.getEvents(
            )
                .subscribeOn(ioScheduler)
                .doOnNext { LOG.v("semaphor read ${channels.size}") }
                .flatMapCompletable { req ->
                    when (req.operation) {
                        GattServerConnection.Operation.CHARACTERISTIC_READ -> {
                            val res = when (req.uuid) {
                                BluetoothLERadioModuleImpl.UUID_SEMAPHOR -> {
                                    selectCharacteristic().flatMapCompletable { characteristic ->
                                        req.sendReply(
                                            BluetoothLERadioModuleImpl.uuid2bytes(
                                                characteristic.uuid
                                            ), BluetoothGatt.GATT_SUCCESS
                                        )
                                    }
                                }
                                else -> Completable.complete()

                            }
                            res
                        }
                        GattServerConnection.Operation.CHARACTERISTIC_WRITE -> {
                            req.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                                .andThen(Completable.defer {
                                    for (lock in channels.values) {
                                        if (lock.isLocked() && lock.uuid == req.uuid) {
                                            val luid =
                                                BluetoothLERadioModuleImpl.bytes2uuid(req.value)!!
                                            LOG.e("server got notf uuid $luid")
                                            return@defer handleQueueItem(
                                                luid,
                                                lock.uuid,
                                                req.remoteDevice
                                            ).doFinally {
                                                lock.release()
                                            }
                                        }
                                    }
                                    Completable.complete()
                                })

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
        disposable.add(d)
    }
}