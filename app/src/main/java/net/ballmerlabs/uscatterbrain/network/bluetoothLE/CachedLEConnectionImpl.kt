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
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionScope
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
open class CachedLEConnectionImpl @Inject constructor(
    @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.TRANS_IO) private val ioScheduler: Scheduler,
    val device: RxBleDevice,
    val advertiser: Advertiser,
    val leState: LeState,
    val luid: UUID
) : CachedLeConnection {
    private val LOG by scatterLog()
    override val mtu = AtomicInteger(512)
    private val disposable = CompositeDisposable()
    override val connection = BehaviorSubject.create<RxBleConnection>()
    private val disconnectCallbacks = ConcurrentHashMap<() -> Completable, Boolean>()
    protected open val channelNotif = InputStreamObserver(8000)
    private val notificationsSetup = BehaviorSubject.create<Boolean>()
    private var subscribed = AtomicBoolean(false)
    private val notifs = AtomicReference<Disposable?>(null)
    override fun subscribeNotifs(): Completable {
        return Completable.defer {
            notificationsSetup.onNext(false)
            notifs.getAndSet(null)?.dispose()
           // channelNotif.clear()
            selectChannel()
                // .takeWhile { (channel === null || channel == currentChannel.get()) || currentChannel.get() == null }
                .doOnNext { b -> LOG.e("client notif bytes ${b.size}") }
                .doOnError { err ->
                    LOG.e("error in channel notifications $err")
                }
                .doFinally {
                    LOG.e("channel notifications for ${device.macAddress} completed")
                    subscribed.set(false)
                    notificationsSetup.onNext(false)
                }
                .doOnDispose { LOG.w("channel notifs disposed") }
                .doOnSubscribe { v -> notifs.set(v) }
                .subscribe(channelNotif)
            awaitNotificationsSetup()
        }
    }

    override fun awaitNotificationsSetup(): Completable {
        return notificationsSetup.takeUntil { v -> v }.ignoreElements()
    }

    override fun subscribeConnection(rawConnection: Observable<RxBleConnection>) {
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

    override fun setOnDisconnect(callback: () -> Completable) {
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

                                            c.writeCharacteristic(
                                                uuid,
                                                BluetoothLERadioModuleImpl.uuid2bytes(
                                                    advertiser.getHashLuid()
                                                )!!
                                            ).ignoreElement()
                                        .andThen(Completable.fromAction {
                                            notificationsSetup.onNext(true)
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
            channelNotif,
            ioScheduler
        ).timeout(44, TimeUnit.SECONDS, ioScheduler)
            .doOnError { err -> LOG.w("failed to receive packet ${parser.parser} for $luid $err") }
            .doOnSuccess { p -> LOG.e("parsed packet len ${p.bytes.size}") }
    }

    /**
     * reads a single advertise packet
     * @return single emitting advertise packet
     */
    override fun readAdvertise(): Single<AdvertisePacket> {
        return cachedNotification(AdvertisePacket.parser())
    }

    /**
     * reads a single upgrade packet
     * @return single emitting upgrade packet
     */
    override fun readUpgrade(): Single<UpgradePacket> {
        return cachedNotification(UpgradePacket.parser())
    }

    /**
     * reads a single blockheader packet
     * @return single emitting blockheader packet
     */
    override fun readBlockHeader(): Single<BlockHeaderPacket> {
        return cachedNotification(BlockHeaderPacket.parser())
    }

    /**
     * reads a single blocksequence packet
     * @return single emitting blocksequence packet
     */
    override fun readBlockSequence(): Single<BlockSequencePacket> {
        return cachedNotification(BlockSequencePacket.parser())
    }

    /**
     * reads a single declarehashes packet
     * @return single emitting delcarehashes packet
     */
    override fun readDeclareHashes(): Single<DeclareHashesPacket> {
        return cachedNotification(DeclareHashesPacket.parser())
    }

    /**
     * reads a single electleader packet
     * @return single emitting electleader packet
     */
    override fun readElectLeader(): Single<ElectLeaderPacket> {
        return cachedNotification(ElectLeaderPacket.parser())
    }

    /**
     * reads a single identity packet
     * @return single emitting identity packet
     */
    override fun readIdentityPacket(): Single<IdentityPacket> {
        return cachedNotification(IdentityPacket.parser())
    }

    /**
     * reads a single luid packet
     * @return single emitting luid packet
     */
    override fun readLuid(): Single<LuidPacket> {
        return cachedNotification(LuidPacket.parser())
    }

    /**
     * reads a single ack packet
     * @return single emititng ack packet
     */
    override fun readAck(): Single<AckPacket> {
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