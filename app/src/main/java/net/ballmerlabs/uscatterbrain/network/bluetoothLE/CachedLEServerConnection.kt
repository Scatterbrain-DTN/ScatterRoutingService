package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
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
                        .map { lockedCharactersitic -> lockedCharactersitic.awaitCharacteristic().toObservable() }
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
        return Completable.fromAction {
                        Log.e(TAG, "queue accepted packet ${packet.type}")
                        packetQueue.accept(packet)
                    }.doOnComplete { Log.v(TAG, "serverNotify for packet " + packet.type) }

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
                .toFlowable(BackpressureStrategy.BUFFER)
                .zipWith(packetQueue.toFlowable(BackpressureStrategy.BUFFER), { req, packet ->
                    Log.v(TAG, "received UUID_SEMAPHOR write ${req.remoteDevice.macAddress}")
                            selectCharacteristic()
                                .flatMapCompletable { characteristic ->
                                    Log.v(TAG, "LOCKED characteristic ${characteristic.uuid}")
                                                    connection.getOnCharacteristicReadRequest(characteristic.uuid)
                                                        .firstOrError()
                                                        .flatMapCompletable { trans ->
                                                            Log.v(TAG, "characteristic ${characteristic.uuid} start indications")
                                                            trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                                                                .andThen(
                                                                    connection.setupIndication(
                                                                        characteristic.uuid,
                                                                        packet.writeToStream(20, scheduler),
                                                                        trans.remoteDevice
                                                                    )
                                                                        .timeout(5, TimeUnit.SECONDS)
                                                                        .doOnError { err -> Log.e(TAG, "characteristic ${characteristic.uuid} err: $err") }
                                                                        .doOnComplete {
                                                                            Log.v(TAG, "indication for packet ${packet.type}, ${characteristic.uuid} finished")
                                                                        }
                                                                )
                                                        }
                                                        .mergeWith(
                                                            req.sendReply(
                                                            BluetoothLERadioModuleImpl.uuid2bytes(characteristic.uuid),
                                                            BluetoothGatt.GATT_SUCCESS
                                                            )
                                                                .doOnComplete { Log.v(TAG, "successfully ACKed ${characteristic.uuid} start indications") }
                                                                .doOnError { err -> Log.e(TAG, "error ACKing ${characteristic.uuid} start indication: $err") }
                                                                .timeout(5, TimeUnit.SECONDS)
                                                        )
                                                        .doOnError{ err ->
                                                            Log.e(TAG, "error in gatt server indication $err")
                                                            errorRelay.accept(err)
                                                        }
                                                        .timeout(5, TimeUnit.SECONDS)
                                                        .onErrorComplete()
                                                        .doFinally {
                                                            Log.v(TAG, "releasing locked characteristic ${characteristic.uuid}")
                                                            characteristic.release()
                                                        }
                                }
                                .onErrorResumeNext { req.sendReply(byteArrayOf(), BluetoothGatt.GATT_FAILURE) }
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