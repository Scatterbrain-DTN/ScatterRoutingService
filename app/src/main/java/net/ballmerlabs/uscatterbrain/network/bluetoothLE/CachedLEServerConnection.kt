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
import io.reactivex.functions.BiFunction
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.LockedCharactersitic
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.OwnedCharacteristic
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CachedLEServerConnection(val connection: RxBleServerConnection, private val channels: ConcurrentHashMap<UUID, LockedCharactersitic>) : Disposable {
    private val disposable = CompositeDisposable()
    private val packetQueue = PublishRelay.create<ScatterSerializable>()
    private val sizeRelay = PublishRelay.create<Int>()
    private val errorRelay = PublishRelay.create<Throwable>() //TODO: handle errors
    private val size = AtomicReference(0)
    fun selectCharacteristic(): Single<OwnedCharacteristic> {
        return Observable.mergeDelayError(
                Observable.fromIterable(channels.values)
                        .map { lockedCharactersitic: LockedCharactersitic -> lockedCharactersitic.awaitCharacteristic().toObservable() }
        )
                .firstOrError()
    }

    fun setDefaultReply(characteristic: UUID?, reply: Int): Disposable {
        val d = connection.getOnCharacteristicWriteRequest(characteristic)
                .subscribe(
                        { trans: ServerResponseTransaction -> trans.sendReply(reply, 0, trans.value).subscribe() }
                ) { err: Throwable -> Log.v(TAG, "failed to set default reply: $err") }
        disposable.add(d)
        return d
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
                .doOnSubscribe { disposable: Disposable? ->
                    Log.v(TAG, "packet queue accepted packet " + packet.type)
                    packetQueue.accept(packet)
                }
    }

    private fun getSize(): Int {
        return size.get()
    }

    private fun decrementSize(): Int {
        return size.updateAndGet { size: Int -> size - 1}
    }

    private fun incrementSize(): Int {
        return size.updateAndGet { size: Int -> size + 1 }
    }

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
                    .timeout(BluetoothLEModule.Companion.TIMEOUT.toLong(), TimeUnit.SECONDS)
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
        val d = connection.getOnCharacteristicReadRequest(
                BluetoothLERadioModuleImpl.Companion.UUID_SEMAPHOR
        )
                .doOnNext { req: ServerResponseTransaction? -> Log.v(TAG, "received timing characteristic write request") }
                .toFlowable(BackpressureStrategy.BUFFER)
                .concatMapSingle { req: ServerResponseTransaction ->
                    selectCharacteristic()
                            .flatMap { characteristic: OwnedCharacteristic ->
                                req.sendReply(BluetoothGatt.GATT_SUCCESS, 0,
                                        BluetoothLERadioModuleImpl.Companion.uuid2bytes(characteristic.uuid))
                                        .toSingleDefault(characteristic)
                            }
                            .timeout(2, TimeUnit.SECONDS)
                            .doOnError { err: Throwable? -> req.sendReply(BluetoothGatt.GATT_FAILURE, 0, null) }
                }
                .zipWith(packetQueue.toFlowable(BackpressureStrategy.BUFFER), BiFunction { ch: OwnedCharacteristic, packet: ScatterSerializable ->
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