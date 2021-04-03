package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import android.util.Log
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleServerConnection
import com.polidea.rxandroidble2.ServerResponseTransaction
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
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
 */
class CachedLEServerConnection(val connection: RxBleServerConnection, private val channels: ConcurrentHashMap<UUID, LockedCharactersitic>) : Disposable {
    private val disposable = CompositeDisposable()
    private val packetQueue = PublishRelay.create<ScatterSerializable>()
    private val sizeRelay = PublishRelay.create<Int>()
    private val errorRelay = PublishRelay.create<Throwable>() //TODO: handle errors
    private val size = AtomicReference(0)

    /*
     * when a client reads from the semaphor characteristic, we need to
     * find an unlocked channel and return its uuid
     */
    private fun selectCharacteristic(): Single<OwnedCharacteristic> {
        return Observable.mergeDelayError(
                Observable.fromIterable(channels.values)
                        .map { lockedCharactersitic: LockedCharactersitic -> lockedCharactersitic.awaitCharacteristic().toObservable() }
        )
                .firstOrError()
    }
    
    @Synchronized
    private fun <T : ScatterSerializable> enqueuePacket(packet: T): Completable {
        incrementSize()
        return sizeRelay
                .mergeWith(
                        errorRelay
                                .flatMap { exception: Throwable -> throw exception }
                )
                .takeWhile { s: Int -> s > 0 }
                .ignoreElements()
                .doOnSubscribe {
                    Log.v(TAG, "packet queue accepted packet " + packet.type)
                    packetQueue.accept(packet)
                }
    }

    private fun decrementSize(): Int {
        return size.updateAndGet { size: Int -> size - 1}
    }

    private fun incrementSize(): Int {
        return size.updateAndGet { size: Int -> size + 1 }
    }

    /**
     * Send a scatterbrain message to the connected client.
     * @param packet ScatterSerializable message to send
     * @return completable
     */
    @Synchronized
    fun <T : ScatterSerializable> serverNotify(
            packet: T
    ): Completable {
        Log.v(TAG, "serverNotify for packet " + packet.type)
        return if (size.get() <= 0) {
            enqueuePacket(packet)
        } else {
            sizeRelay
                    .takeWhile { s: Int -> s > 0 }
                    .ignoreElements()
                    .andThen(enqueuePacket(packet))
                    .timeout(BluetoothLEModule.TIMEOUT.toLong(), TimeUnit.SECONDS)
        }
    }

    override fun dispose() {
        disposable.dispose()
    }

    override fun isDisposed(): Boolean {
        return disposable.isDisposed
    }

    companion object {
        const val TAG = "CachedLEServerConnection"
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
        val d = connection.getOnCharacteristicReadRequest(
                BluetoothLERadioModuleImpl.UUID_SEMAPHOR
        )
                .doOnNext { Log.v(TAG, "received timing characteristic write request") }
                .toFlowable(BackpressureStrategy.BUFFER)
                .concatMapSingle { req: ServerResponseTransaction ->
                    selectCharacteristic()
                            .flatMap { characteristic: OwnedCharacteristic ->
                                req.sendReply(BluetoothGatt.GATT_SUCCESS, 0,
                                        BluetoothLERadioModuleImpl.uuid2bytes(characteristic.uuid))
                                        .toSingleDefault(characteristic)
                            }
                            .timeout(2, TimeUnit.SECONDS)
                            .doOnError { req.sendReply(BluetoothGatt.GATT_FAILURE, 0, null) }
                }
                .zipWith(packetQueue.toFlowable(BackpressureStrategy.BUFFER), { ch: OwnedCharacteristic, packet: ScatterSerializable ->
                    Log.v(TAG, "server received timing characteristic write")
                    connection.getOnCharacteristicReadRequest(ch.uuid)
                            .firstOrError()
                            .timeout(5, TimeUnit.SECONDS)
                            .flatMapCompletable { trans -> trans.sendReply(BluetoothGatt.GATT_SUCCESS, 0, null) }
                            .andThen(connection.setupIndication(ch.uuid, packet.writeToStream(20)))
                            .doOnError(errorRelay)
                            .doFinally {
                                sizeRelay.accept(decrementSize())
                                ch.release()
                            }
                })
                .flatMapCompletable { completable: Completable -> completable }
                .repeat()
                .retry()
                .subscribe(
                        { Log.v(TAG, "timing characteristic write handler completed") }
                ) { err: Throwable -> Log.e(TAG, "timing characteristic handler error: $err") }
        disposable.add(d)
    }
}