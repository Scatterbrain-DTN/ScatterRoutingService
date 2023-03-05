package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import com.google.protobuf.MessageLite
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.Companion.GATT_SIZE
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.OwnedCharacteristic
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private val packetQueue = PublishRelay.create<Pair<ScatterSerializable<out MessageLite>, Int>>()
    private val errorRelay = PublishRelay.create<Throwable>() //TODO: handle errors
    private val cookies = AtomicReference(0)

    private fun getCookie(): Int {
        return cookies.getAndUpdate { v ->
            Math.floorMod(v + 1, Int.MAX_VALUE)
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
        packet: ScatterSerializable<T>
    ): Completable {
        val cookie = getCookie()
        return Completable.fromAction {
            packetQueue.accept(Pair(packet, cookie))
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
            connection.getOnCharacteristicReadRequest(
                BluetoothLERadioModuleImpl.UUID_SEMAPHOR
            )
                .subscribeOn(ioScheduler)
                .doOnNext { LOG.v("semaphor read ${channels.size}") }
                .toFlowable(BackpressureStrategy.BUFFER)
                .zipWith(packetQueue.toFlowable(BackpressureStrategy.BUFFER)) { req, packet ->
                    LOG.v("received UUID_SEMAPHOR write ${req.remoteDevice.macAddress} packet: ${packet.first.type}")
                    selectCharacteristic()
                        .flatMapCompletable { characteristic ->
                            LOG.v("LOCKED characteristic ${characteristic.uuid} packet: ${packet.first.type}")

                            req.sendReply(
                                BluetoothLERadioModuleImpl.uuid2bytes(characteristic.uuid),
                                BluetoothGatt.GATT_SUCCESS
                            )
                                .doOnComplete { LOG.v("successfully ACKed ${characteristic.uuid} start indications") }
                                .doOnError { err -> LOG.e("error ACKing ${characteristic.uuid} start indication: $err") }
                                .andThen(
                                    connection.setupIndication(
                                        characteristic.uuid,
                                        packet.first.writeToStream(GATT_SIZE, scheduler),
                                        req.remoteDevice
                                    )
                                )
                                .doOnError { err -> LOG.e("characteristic ${characteristic.uuid} err: $err") }
                                .doOnComplete {
                                    LOG.v("indication for packet ${packet.first.type}, ${characteristic.uuid} finished")
                                }
                                .doOnError { err ->
                                    LOG.e("error in gatt server indication $err")
                                    errorRelay.accept(err)
                                }
                                .onErrorComplete()
                                .doFinally {
                                    LOG.v("releasing locked characteristic ${characteristic.uuid}")
                                    characteristic.release()
                                }
                        }
                        .doOnError { err ->
                            LOG.e("error in gatt server selectCharacteristic: $err")
                        }
                        .onErrorComplete()
                }
                .flatMapCompletable { obs -> obs }
                .subscribeOn(ioScheduler)
                .subscribe(
                    {
                        LOG.e("timing characteristic write handler completed prematurely")
                    },
                    { err -> LOG.e("timing characteristic handler error: $err") }
                )
        disposable.add(d)
    }
}