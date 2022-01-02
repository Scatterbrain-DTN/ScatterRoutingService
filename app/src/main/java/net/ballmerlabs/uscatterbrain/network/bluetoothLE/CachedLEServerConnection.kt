package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import android.icu.number.IntegerWidth
import android.util.Log
import com.google.protobuf.MessageLite
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleServerConnection
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.LockedCharactersitic
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.OwnedCharacteristic
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
        val connection: RxBleServerConnection,
        private val channels: ConcurrentHashMap<UUID, LockedCharactersitic>,
        private  val scheduler: Scheduler
        ) : Disposable {
    val luid: UUID? = null
    private val disposable = CompositeDisposable()
    private val packetQueue = PublishRelay.create<Pair<ScatterSerializable<out MessageLite>, Int>>()
    private val errorRelay = PublishRelay.create<Throwable>() //TODO: handle errors
    private val cookies = AtomicReference(0)
    private val cookieCompleteRelay = PublishRelay.create<Int>()

    private fun getCookie(): Int {
        return cookies.getAndUpdate { v ->
            Math.floorMod(v+1, Int.MAX_VALUE)
        }
    }

    /**
     * when a client reads from the semaphor characteristic, we need to
     * find an unlocked channel and return its uuid
     *
     * @return single emitting characteristic selected
     */
    private fun selectCharacteristic(): Single<OwnedCharacteristic> {
        return Observable.mergeDelayError(
                Observable.fromIterable(channels.values)
                        .map { lockedCharactersitic -> lockedCharactersitic.awaitCharacteristic().toObservable() }
                    .subscribeOn(scheduler)
        )
                .firstOrError()
                .doOnSuccess { char -> Log.v(TAG, "selected characteristic $char") }
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
        return cookieCompleteRelay.takeUntil { v -> v == cookie }
            .doOnSubscribe {
                Log.v(TAG, "serverNotify ACCEPTED packet ${packet.type} cookie: $cookie")
                packetQueue.accept(Pair(packet, cookie))
            }
            .ignoreElements()
            .timeout(20, TimeUnit.SECONDS)
            .doOnComplete { Log.v(TAG, "serverNotify COMPLETED for ${packet.type} cookie $cookie") }

    }

    /**
     * dispose this connected
     */
    override fun dispose() {
        Log.e(TAG, "CachedLEServerConnection disposed")
        disposable.dispose()
    }

    /**
     * return true if this connection is disposed
     * @return is disposed
     */
    override fun isDisposed(): Boolean {
        return disposable.isDisposed
    }

    companion object {
        const val TAG = "LEServerConn"
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
                .subscribeOn(scheduler)
                .toFlowable(BackpressureStrategy.BUFFER)
                .zipWith(packetQueue.toFlowable(BackpressureStrategy.BUFFER), { req, packet ->
                    Log.v(TAG, "received UUID_SEMAPHOR write ${req.remoteDevice.macAddress} packet: ${packet.first.type}")
                            selectCharacteristic()
                                .flatMapCompletable { characteristic ->
                                    Log.v(TAG, "LOCKED characteristic ${characteristic.uuid} packet: ${packet.first.type}")
                                                    connection.getOnCharacteristicReadRequest(characteristic.uuid)
                                                        .subscribeOn(scheduler)
                                                        .firstOrError()
                                                        .flatMapCompletable { trans ->
                                                            Log.v(TAG, "characteristic ${characteristic.uuid} start indications packet: ${packet.first.type}")
                                                            trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                                                                .andThen(
                                                                    connection.setupIndication(
                                                                        characteristic.uuid,
                                                                        packet.first.writeToStream(20, scheduler),
                                                                        trans.remoteDevice
                                                                    )
                                                                        .timeout(20, TimeUnit.SECONDS)
                                                                        .doOnError { err -> Log.e(TAG, "characteristic ${characteristic.uuid} err: $err") }
                                                                        .doOnComplete {
                                                                            Log.v(TAG, "indication for packet ${packet.first.type}, ${characteristic.uuid} finished")
                                                                        }
                                                                )
                                                        }
                                                        .mergeWith(
                                                            req.sendReply(
                                                            BluetoothLERadioModuleImpl.uuid2bytes(characteristic.uuid),
                                                            BluetoothGatt.GATT_SUCCESS
                                                            )
                                                                .subscribeOn(scheduler)
                                                                .doOnComplete { Log.v(TAG, "successfully ACKed ${characteristic.uuid} start indications") }
                                                                .doOnError { err -> Log.e(TAG, "error ACKing ${characteristic.uuid} start indication: $err") }
                                                        )
                                                        .doOnError{ err ->
                                                            Log.e(TAG, "error in gatt server indication $err")
                                                            errorRelay.accept(err)
                                                        }
                                                        .timeout(20, TimeUnit.SECONDS)
                                                        .onErrorComplete()
                                                        .doFinally {
                                                            Log.v(TAG, "releasing locked characteristic ${characteristic.uuid}")
                                                            characteristic.release()
                                                            cookieCompleteRelay.accept(packet.second)
                                                        }
                                }
                                .doOnError { err ->
                                    Log.e(TAG, "error in gatt server selectCharacteristic: $err")
                                }
                                .onErrorComplete()
                })
                .flatMapCompletable { obs -> obs }
                .subscribeOn(scheduler)
                .repeat()
                .retry()
                .subscribe(
                        { Log.e(TAG, "timing characteristic write handler completed prematurely") },
                        { err -> Log.e(TAG, "timing characteristic handler error: $err") }
                )
        disposable.add(d)
    }
}