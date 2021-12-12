package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.util.Log
import com.polidea.rxandroidble2.NotificationSetupMode
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.LockedCharactersitic
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Convenience class wrapping an RxBleConnection
 *
 * This class manages channel selection and protobuf stream parsing
 * for a BLE client connection
 *
 * @property connection raw connection object being wrapped by this class
 */
class CachedLEConnection(
    rawConnection: Observable<RxBleConnection>,
    private val channels: ConcurrentHashMap<UUID, LockedCharactersitic>,
    private val scheduler: Scheduler,
    val device: RxBleDevice
        ) : Disposable {
    private val disposable = CompositeDisposable()
    private val enabled = CompletableSubject.create()
    private val timeout: Long = 20

    val connection = BehaviorSubject.create<RxBleConnection>()
    private val disconnectCallbacks = ConcurrentHashMap<() -> Completable, Boolean>()

    init {
        rawConnection
            .doOnSubscribe { disp ->
                disposable.add(disp)
                Log.v(TAG, "subscribed to connection subject")
            }
            .doOnError { err ->
                Log.e(TAG, "raw connection error: $err")
                onDisconnect().blockingAwait()
            }
            .doOnComplete { Log.e(TAG, "raw connection completed") }
            .concatWith(onDisconnect())
            .subscribe(connection)

        premptiveEnable().subscribe(enabled)
    }

    private fun onDisconnect(): Completable {
        return Observable.fromIterable(disconnectCallbacks.keys)
            .flatMapCompletable { k -> k() }
    }

    fun setOnDisconnect(callback: () -> Completable) {
        disconnectCallbacks[callback] = true
    }

    /**
     * to avoid data races when writing to the ClientConfig descriptor, 
     * enable indications for all channel characteristics
     * as soon as we have the RxBleConnection.
     * @return Completable
     */
    private fun premptiveEnable(): Completable {
        return Observable.fromIterable(channels.keys)
                .flatMapSingle{ uuid: UUID ->
                    connection
                        .firstOrError()
                        .flatMap { c ->
                            c.setupIndication(uuid, NotificationSetupMode.DEFAULT)
                                .doOnNext { Log.v(TAG, "preemptively enabled indications for $uuid") }
                                .doOnError { Log.e(TAG, "failed to preemptively enable indications for $uuid") }
                                .firstOrError()
                        }

                }
                .ignoreElements()
                .onErrorComplete() //We can swallow errors here since indications are enabled already if failed
                .doOnComplete { Log.v(TAG, "all notifications enabled")}
    }

    /**
     * read from the semaphor characteristic to determine what channel
     * we are allowed to use
     * @return Single emitting uuid of channel selected
     */
    private fun selectChannel(): Single<UUID> {
        return connection
            .firstOrError()
            .flatMap { c ->
                c.readCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
                    .map{ bytes: ByteArray ->
                        val uuid = BluetoothLERadioModuleImpl.bytes2uuid(bytes)!!
                        if (!channels.containsKey(uuid)) {
                            throw IllegalStateException("gatt server returned invalid uuid")
                        }
                        uuid
                    }
                }
                .doOnSuccess{ uuid -> Log.v(TAG, "client selected channel $uuid") }
    }

    /**
     * select a free channel and read protobuf data from it.
     * use characteristic reads for timing to tell the server we are ready
     * to receive data
     * @return observable emitting bytes received
     */
    private fun cachedNotification(): Observable<ByteArray> {
        return enabled.andThen(selectChannel())
                .flatMapObservable { uuid: UUID ->
                    connection
                        .firstOrError()
                        .flatMapObservable { c ->
                            c.setupIndication(uuid, NotificationSetupMode.QUICK_SETUP)
                                .mergeWith(c.readCharacteristic(uuid).ignoreElement())
                                .flatMap { observable -> observable }
                                .doOnComplete { Log.e(TAG, "notifications completed for some reason") }
                                .doOnNext { b: ByteArray -> Log.v(TAG, "client received bytes " + b.size) }
                                .timeout(BluetoothLEModule.TIMEOUT.toLong(), TimeUnit.SECONDS)
                        }
                }
    }

    /**
     * reads a single advertise packet
     * @return single emitting advertise packet
     */
    fun readAdvertise(): Single<AdvertisePacket> {
        return ScatterSerializable.parseWrapperFromCRC(
                AdvertisePacket.parser(), cachedNotification(), scheduler
        )
            .doOnSubscribe { Log.v(TAG, "called readAdvertise") }
            .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    /**
     * reads a single upgrade packet
     * @return single emitting upgrade packet
     */
    fun readUpgrade(): Single<UpgradePacket> {
        return ScatterSerializable.parseWrapperFromCRC(
            UpgradePacket.parser(), cachedNotification(), scheduler
        )
            .doOnSubscribe { Log.v(TAG, "called readUpgrade") }
            .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    /**
     * reads a single blockheader packet
     * @return single emitting blockheader packet
     */
    fun readBlockHeader(): Single<BlockHeaderPacket> {
        return ScatterSerializable.parseWrapperFromCRC(
                BlockHeaderPacket.parser(), cachedNotification(), scheduler
        )
            .doOnSubscribe { Log.v(TAG, "called readBlockHeader") }
            .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    /**
     * reads a single blocksequence packet
     * @return single emitting blocksequence packet
     */
    fun readBlockSequence(): Single<BlockSequencePacket> {
        return ScatterSerializable.parseWrapperFromCRC(
                BlockSequencePacket.parser(), cachedNotification(), scheduler
        )
            .doOnSubscribe { Log.v(TAG, "called readBlockSequence") }
            .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    /**
     * reads a single declarehashes packet
     * @return single emitting delcarehashes packet
     */
    fun readDeclareHashes(): Single<DeclareHashesPacket> {
        return ScatterSerializable.parseWrapperFromCRC(
                DeclareHashesPacket.parser(), cachedNotification(), scheduler
        )
            .doOnSubscribe { Log.v(TAG, "called readDeclareHashes") }
            .timeout(timeout, TimeUnit.SECONDS, scheduler)

    }

    /**
     * reads a single electleader packet
     * @return single emitting electleader packet
     */
    fun readElectLeader(): Single<ElectLeaderPacket> {
        return ScatterSerializable.parseWrapperFromCRC(
                ElectLeaderPacket.parser(), cachedNotification(), scheduler
        )
            .doOnSubscribe { Log.v(TAG, "called readElectLeader") }
            .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    /**
     * reads a single identity packet
     * @return single emitting identity packet
     */
    fun readIdentityPacket(): Single<IdentityPacket> {
        return ScatterSerializable.parseWrapperFromCRC(
                IdentityPacket.parser(), cachedNotification(), scheduler
        )
            .doOnSubscribe { Log.v(TAG, "called readIdentityPacket") }
            .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    /**
     * reads a single luid packet
     * @return single emitting luid packet
     */
    fun readLuid(): Single<LuidPacket> {
        return ScatterSerializable.parseWrapperFromCRC(
                LuidPacket.parser(), cachedNotification(), scheduler
        )
            .doOnSubscribe { Log.v(TAG, "called readLuid") }
            .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    /**
     * dispose this connection
     */
    override fun dispose() {
        Log.e(TAG, "CachedLEConnection disposed")
        disposable.dispose()
    }

    /**
     * returns true if this connection is disposed
     */
    override fun isDisposed(): Boolean {
        return disposable.isDisposed
    }

    companion object {
        const val TAG = "CachedLEConnection"
    }

}