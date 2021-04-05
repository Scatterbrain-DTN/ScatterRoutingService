package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.Context
import android.util.Log
import com.polidea.rxandroidble2.NotificationSetupMode
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.Timeout
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
 */
class CachedLEConnection(
        private val connectionObservable: Observable<RxBleConnection>,
        private val channels: ConcurrentHashMap<UUID, LockedCharactersitic>,
        private val scheduler: Scheduler
        ) : Disposable {
    private val disposable = CompositeDisposable()
    private val enabled = CompletableSubject.create()
    private val connection = BehaviorSubject.create<RxBleConnection>()
    private val timeout: Long = 5

    init {
        premptiveEnable().subscribe(enabled)
        connectionObservable
                .doOnSubscribe { d -> disposable.add(d)}
                .subscribe(connection)
    }

    /*
     * to avoid data races when writing to the ClientConfig descriptor, 
     * enable indications for all channel characteristics
     * as soon as we have the RxBleConnection. 
     */
    private fun premptiveEnable(): Completable {
        return Observable.fromIterable(BluetoothLERadioModuleImpl.channels.keys)
                .flatMapSingle{ uuid: UUID ->
                    connection
                            .firstOrError()
                            .flatMap { c ->
                                c.setupIndication(uuid, NotificationSetupMode.DEFAULT)
                                    .doOnNext { Log.v(TAG, "preemptively enabled indications for $uuid") }
                                    .doOnError { Log.e(TAG, "failed to preemptively enable indications for $uuid") }
                                    .firstOrError()
                                    .retry(8) }

                }
                .ignoreElements()
                .onErrorComplete() //We can swallow errors here since indications are enabled already if failed
                .doOnComplete { Log.v(TAG, "all notifications enabled")}
    }

    /*
     * read from the semaphor characteristic to determine what channel
     * we are allowed to use
     */
    private fun selectChannel(): Single<UUID> {
        return connection
                .firstOrError()
                .flatMap { c ->
                    c.readCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
                        .flatMap flatMap@{ bytes: ByteArray ->
                            val uuid: UUID = BluetoothLERadioModuleImpl.bytes2uuid(bytes)
                            if (!channels.containsKey(uuid)) {
                                return@flatMap Single.error<UUID>(IllegalStateException("gatt server returned invalid uuid"))
                            }
                            Single.just(uuid)
                        }
                }
    }

    /*
     * select a free channel and read protobuf data from it.
     * use characteristic reads for timing to tell the server we are ready
     * to receive data
     */
    private fun cachedNotification(): Observable<ByteArray> {
        val notificationDisposable = CompositeDisposable()
        return enabled.andThen(selectChannel())
                .retry(10)
                .flatMapObservable { uuid: UUID ->
                    connection
                            .firstOrError()
                            .flatMapObservable { c ->  c.setupIndication(uuid, NotificationSetupMode.QUICK_SETUP)
                                    .retry(10)
                                    .flatMap { observable: Observable<ByteArray>? -> observable }
                                    .doOnSubscribe {
                                        Log.v(TAG, "client subscribed to notifications for $uuid")
                                        c.readCharacteristic(uuid)
                                                .subscribe()
                                    }
                                    .doOnComplete { Log.e(TAG, "notifications completed for some reason") }
                                    .doOnNext { b: ByteArray -> Log.v(TAG, "client received bytes " + b.size) }
                                    .timeout(BluetoothLEModule.TIMEOUT.toLong(), TimeUnit.SECONDS)
                                    .doFinally { notificationDisposable.dispose() }}
                }
    }

    fun readAdvertise(): Single<AdvertisePacket> {
        return AdvertisePacket.parseFrom(cachedNotification())
                .subscribeOn(scheduler)
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    fun readUpgrade(): Single<UpgradePacket> {
        return UpgradePacket.parseFrom(cachedNotification())
                .subscribeOn(scheduler)
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    fun readBlockHeader(): Single<BlockHeaderPacket> {
        return BlockHeaderPacket.parseFrom(cachedNotification())
                .subscribeOn(scheduler)
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    fun readBlockSequence(): Single<BlockSequencePacket> {
        return BlockSequencePacket.parseFrom(cachedNotification())
                .subscribeOn(scheduler)
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    fun readDeclareHashes(): Single<DeclareHashesPacket> {
        return DeclareHashesPacket.parseFrom(cachedNotification())
                .subscribeOn(scheduler)
                .timeout(timeout, TimeUnit.SECONDS, scheduler)

    }

    fun readElectLeader(): Single<ElectLeaderPacket> {
        return ElectLeaderPacket.parseFrom(cachedNotification())
                .subscribeOn(scheduler)
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    fun readIdentityPacket(context: Context): Single<IdentityPacket> {
        return IdentityPacket.parseFrom(cachedNotification(), context)
                .subscribeOn(scheduler)
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    fun readLuid(): Single<LuidPacket> {
        return LuidPacket.parseFrom(cachedNotification())
                .subscribeOn(scheduler)
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
    }

    override fun dispose() {
        disposable.dispose()
    }

    override fun isDisposed(): Boolean {
        return disposable.isDisposed
    }

    companion object {
        const val TAG = "CachedLEConnection"
    }

}