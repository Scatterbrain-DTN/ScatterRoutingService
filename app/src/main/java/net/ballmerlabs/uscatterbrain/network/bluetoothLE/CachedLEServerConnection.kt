package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import android.util.Log
import com.google.protobuf.MessageLite
import com.jakewharton.rxrelay2.BehaviorRelay
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
    private val disposable = CompositeDisposable()
    private val packetQueue = PublishRelay.create<ScatterSerializable<out MessageLite>>()
    private val lockRelay = BehaviorRelay.create<Boolean>()
    private val errorRelay = PublishRelay.create<Throwable>() //TODO: handle errors

    /**
     * when a client reads from the semaphor characteristic, we need to
     * find an unlocked channel and return its uuid
     *
     * @return single emitting characteristic selected
     */
    private fun selectCharacteristic(): Single<OwnedCharacteristic> {
        return Observable.mergeDelayError(
                Observable.fromIterable(channels.values)
                        .map { lockedCharactersitic: LockedCharactersitic -> lockedCharactersitic.awaitCharacteristic().toObservable() }
        )
                .firstOrError()
                .doOnSuccess { char -> Log.v(TAG, "selected characteristic $char") }
    }

    /**
     * Send a scatterbrain message to the connected client.
     * @param packet ScatterSerializable message to send
     * @return completable
     */
    fun <T : MessageLite>serverNotify(
            packet: ScatterSerializable<T>
    ): Completable {
        return lockRelay.takeUntil { v -> !v }.ignoreElements()
                .andThen(
                    Completable.fromAction {
                        packetQueue.accept(packet)
                    }
                            .doOnSubscribe { lockRelay.accept(true) }
                            .andThen(lockRelay.takeUntil { v -> !v }.ignoreElements())
                ).doOnComplete { Log.v(TAG, "serverNotify for packet " + packet.type) }

    }

    /**
     * dispose this connected
     */
    override fun dispose() {
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
        lockRelay.accept(false)
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
        packetQueue.toFlowable(BackpressureStrategy.BUFFER)
                .zipWith(connection.getOnCharacteristicReadRequest(
                        BluetoothLERadioModuleImpl.UUID_SEMAPHOR
                )
                        .toFlowable(BackpressureStrategy.BUFFER), { packet, req ->
                    Log.v(TAG, "received UUID_SEMAPHOR write")
                            selectCharacteristic()
                                    .flatMapCompletable { characteristic ->
                                                    connection.getOnCharacteristicReadRequest(characteristic.uuid)
                                                            .firstOrError()
                                                            .flatMapCompletable { trans -> trans.sendReply(BluetoothGatt.GATT_SUCCESS, 0, null) }
                                                            .andThen(connection.setupIndication(
                                                                    characteristic.uuid,
                                                                    packet.writeToStream(20, scheduler)
                                                            ))
                                                            .doOnError{ err ->
                                                                Log.e(TAG, "error in gatt server indication $err")
                                                                errorRelay.accept(err)
                                                            }
                                                            .onErrorComplete()
                                                            .doFinally {
                                                                characteristic.release()
                                                                lockRelay.accept(false)
                                                            }
                                                            .doOnSubscribe {
                                                                req.sendReply(BluetoothGatt.GATT_SUCCESS, 0,
                                                                    BluetoothLERadioModuleImpl.uuid2bytes(characteristic.uuid))
                                                                    .subscribe(
                                                                            { },
                                                                            { err -> Log.e(TAG, "failed to ack semaphor read: $err")}
                                                                    )
                                                            }
                                    }
                                    .doOnError { err ->
                                        Log.e(TAG, "error in gatt server selectCharacteristic: $err")
                                        req.sendReply(BluetoothGatt.GATT_FAILURE, 0, null)
                                    }
                                    .onErrorComplete()
                })
                .flatMapCompletable { obs -> obs }
                .repeat()
                .retry()
                .subscribe(
                        { Log.e(TAG, "timing characteristic write handler completed prematurely") },
                        { err -> Log.e(TAG, "timing characteristic handler error: $err") }
                )
        disposable.add(d)
    }
}