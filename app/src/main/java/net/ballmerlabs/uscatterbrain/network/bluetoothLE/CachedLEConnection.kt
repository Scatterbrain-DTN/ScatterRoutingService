package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.google.protobuf.MessageLite
import com.polidea.rxandroidble2.NotificationSetupMode
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionScope
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named

/**
 * Convenience class wrapping an RxBleConnection
 *
 * This class manages channel selection and protobuf stream parsing
 * for a BLE client connection
 *
 * @property connection raw connection object being wrapped by this class
 */
@ScatterbrainTransactionScope
class CachedLEConnection @Inject constructor(
    @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_PARSE) private val scheduler: Scheduler,
    @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.TRANS_IO) private val ioScheduler: Scheduler,
    val device: RxBleDevice,
    val advertiser: Advertiser,
    val leState: LeState,
    val luid: UUID
) : Disposable {
    private val LOG by scatterLog()
    private val disposable = CompositeDisposable()
    val connection = BehaviorSubject.create<RxBleConnection>()
    private val disconnectCallbacks = ConcurrentHashMap<() -> Completable, Boolean>()
    private val channelNotif = AtomicReference(InputStreamObserver(8000))
    private val notificationsSetup = AtomicReference(CompletableSubject.create())
    private var subscribed = AtomicBoolean(false)

    fun subscribeNotifs() {
        channelNotif.updateAndGet {
            val new = InputStreamObserver(8000)
            selectChannel()
                .doOnNext { b -> LOG.e("client notif bytes ${b.size}") }
                .doOnError { err ->
                    LOG.e("error in channel notifications $err")
                }
                .doFinally {
                    LOG.e("channel notifications for ${device.macAddress} completed")
                    subscribed.set(false)
                }
                .subscribe(new)
            new
        }
    }

    fun awaitNotificationsSetup(): Completable {
        return notificationsSetup.get()
    }

    fun subscribeConnection(rawConnection: Observable<RxBleConnection>) {
        rawConnection.doOnSubscribe { disp ->
            disposable.add(disp)
            LOG.v("subscribed to connection subject")
        }.onErrorResumeNext { err: Throwable ->
            if (err is BleDisconnectedException) {
                Observable.empty()
            } else {
                Observable.error(err)
            }
        }
            .doOnError { err ->
                LOG.e("raw connection error: $err")
            }
            .doOnComplete { LOG.e("raw connection completed") }
            .subscribe(connection)
    }

    private fun onDisconnect(): Completable {
        return Observable.fromIterable(disconnectCallbacks.keys)
            .flatMapCompletable { k -> k() }
    }

    fun setOnDisconnect(callback: () -> Completable) {
        disconnectCallbacks[callback] = true
    }


    /**
     * read from the semaphor characteristic to determine what channel
     * we are allowed to use
     * @return Single emitting uuid of channel selected
     */
    private fun selectChannel(): Observable<ByteArray> {
        return connection
            .flatMap { c ->

                c.readCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
                    .flatMapObservable { bytes ->
                        val uuid = BluetoothLERadioModuleImpl.bytes2uuid(bytes)!!
                        LOG.e("client selected channel $uuid for $luid")
                        c.setupNotification(uuid, NotificationSetupMode.QUICK_SETUP)
                            .flatMap { obs ->
                                LOG.e("client notifications setup")
                                obs
                                    .mergeWith(
                                        Completable.timer(1, TimeUnit.SECONDS, ioScheduler).andThen(
                                            c.writeCharacteristic(
                                                uuid,
                                                BluetoothLERadioModuleImpl.uuid2bytes(
                                                    advertiser.getHashLuid()
                                                )!!
                                            ).ignoreElement()
                                        ).andThen(Completable.fromAction {
                                            notificationsSetup.getAndSet(CompletableSubject.create())?.onComplete()
                                        })
                                    )
                            }
                    }
            }
    }

    /**
     * select a free channel and read protobuf data from it.
     * use characteristic reads for timing to tell the server we are ready
     * to receive data
     * @return observable emitting bytes received
     */
    private inline fun <reified T : ScatterSerializable<R>, reified R : MessageLite> cachedNotification(
        parser: ScatterSerializable.Companion.Parser<R, T>
    ): Single<T> {
        return ScatterSerializable.parseWrapperFromCRC(
            parser,
            channelNotif.get(),
            ioScheduler
        ).timeout(44, TimeUnit.SECONDS, ioScheduler)
            .doOnError { err -> LOG.w("failed to receive packet for $luid $err") }
            .doOnSuccess { p -> LOG.e("parsed packet len ${p.bytes.size}") }
    }

    /**
     * reads a single advertise packet
     * @return single emitting advertise packet
     */
    fun readAdvertise(): Single<AdvertisePacket> {
        return cachedNotification(AdvertisePacket.parser())
    }

    /**
     * reads a single upgrade packet
     * @return single emitting upgrade packet
     */
    fun readUpgrade(): Single<UpgradePacket> {
        return cachedNotification(UpgradePacket.parser())
    }

    /**
     * reads a single blockheader packet
     * @return single emitting blockheader packet
     */
    fun readBlockHeader(): Single<BlockHeaderPacket> {
        return cachedNotification(BlockHeaderPacket.parser())
    }

    /**
     * reads a single blocksequence packet
     * @return single emitting blocksequence packet
     */
    fun readBlockSequence(): Single<BlockSequencePacket> {
        return cachedNotification(BlockSequencePacket.parser())
    }

    /**
     * reads a single declarehashes packet
     * @return single emitting delcarehashes packet
     */
    fun readDeclareHashes(): Single<DeclareHashesPacket> {
        return cachedNotification(DeclareHashesPacket.parser())
    }

    /**
     * reads a single electleader packet
     * @return single emitting electleader packet
     */
    fun readElectLeader(): Single<ElectLeaderPacket> {
        return cachedNotification(ElectLeaderPacket.parser())
    }

    /**
     * reads a single identity packet
     * @return single emitting identity packet
     */
    fun readIdentityPacket(): Single<IdentityPacket> {
        return cachedNotification(IdentityPacket.parser())
    }

    /**
     * reads a single luid packet
     * @return single emitting luid packet
     */
    fun readLuid(): Single<LuidPacket> {
        return cachedNotification(LuidPacket.parser())
    }

    /**
     * reads a single ack packet
     * @return single emititng ack packet
     */
    fun readAck(): Single<AckPacket> {
        return cachedNotification(AckPacket.parser())
    }

    /**
     * dispose this connection
     */
    override fun dispose() {
        LOG.e("CachedLEConnection disposed")
        disposable.dispose()
    }

    /**
     * returns true if this connection is disposed
     */
    override fun isDisposed(): Boolean {
        return disposable.isDisposed
    }
}