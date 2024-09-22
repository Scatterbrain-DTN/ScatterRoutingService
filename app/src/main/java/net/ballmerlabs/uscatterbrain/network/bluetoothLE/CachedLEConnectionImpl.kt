package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.google.protobuf.MessageLite
import com.polidea.rxandroidble2.NotificationSetupMode
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleAlreadyConnectedException
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionScope
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.proto.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnectionImpl
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.scatterproto.Optional
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
    @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_PARSE) private val parseScheduler: Scheduler,
    val device: RxBleDevice,
    val advertiser: Advertiser,
    val leState: LeState,
    val luid: UUID
) : CachedLeConnection {
    private val LOG by scatterLog()
    private val disposable = CompositeDisposable()
    override val connection = BehaviorSubject.create<RxBleConnection>()
    private val disconnectCallbacks = ConcurrentHashMap<() -> Completable, Boolean>()
    protected open val channelNotif = InputStreamObserver(1024*2)
    private val notificationsSetup = BehaviorSubject.create<Optional<UUID>>()
    private var subscribed = AtomicBoolean(false)
    private val notifs = AtomicReference<Disposable?>(null)
    private val errorSubject = BehaviorSubject.create<Throwable>()
    override fun subscribeNotifs(): Completable {
        return Completable.defer {
            LOG.v("subscribeNotifs")
            notificationsSetup.onNext(Optional.empty())
            val n = notifs.get()
            if (n == null) {
                // channelNotif.clear()
                LOG.v("try select channeld")
                selectChannel()
                    .doOnSubscribe { disp -> disposable.add(disp) }
                    // .takeWhile { (channel === null || channel == currentChannel.get()) || currentChannel.get() == null }
                    .doOnNext { b -> LOG.e("client notif bytes ${b.size}") }
                    .doOnError { err ->
                        LOG.e("error in channel notifications $err")
                        errorSubject.onNext(err)
                    }
                    .doFinally {
                        LOG.e("channel notifications for ${device.macAddress} completed")
                        subscribed.set(false)
                        notificationsSetup.onNext(Optional.empty())
                    }
                    .doOnDispose { LOG.w("channel notifs disposed") }
                    .doOnSubscribe { v -> notifs.set(v) }
                    .subscribe(channelNotif)
                awaitNotificationsSetup()
            } else {
                LOG.v("notifs were null?")
                Completable.complete()
            }
        }
    }

    override fun sendForget(luid: UUID): Completable {
        return connection.firstOrError().flatMapCompletable { c ->
            LOG.v("sending forget $luid")
            c.writeCharacteristic(
                BluetoothLERadioModuleImpl.UUID_FORGET,
                uuid2bytes(luid)!!
            ).ignoreElement()
        }
    }

    override fun awaitNotificationsSetup(): Completable {
        return notificationsSetup.takeUntil { v -> v.isPresent }
            .lastOrError()
            .flatMapCompletable { uuid ->
                connection.firstOrError().flatMapCompletable { c ->
                    c.writeCharacteristic(
                        uuid.item!!,
                        uuid2bytes(
                            advertiser.getHashLuid()
                        )!!
                    ).ignoreElement()
                }
            }
    }


    override fun subscribeConnection(rawConnection: Observable<RxBleConnection>) {
        disposable.add(channelNotif)
        rawConnection.doOnSubscribe { disp ->
            disposable.add(disp)
            LOG.v("subscribed to connection subject")
        }
            .doOnComplete { LOG.e("raw connection completed") }
            .doOnNext { LOG.w("got connection") }
            .onErrorResumeNext{ err: Throwable -> onDisconnect().andThen(Observable.error(err)) }
            .concatWith(onDisconnect())
            .subscribe(connection)
        val disp = connection.subscribe(
            {},
            {}
        )

        disposable.add(disp)
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
                LOG.v("selectChannel")
                c.readCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
                    .flatMapObservable { bytes ->
                        val uuid = bytes2uuid(bytes)
                        LOG.e("client selected channel $uuid for $luid")
                        c.setupIndication(uuid!!, NotificationSetupMode.QUICK_SETUP)
                            .flatMap { obs ->
                                LOG.e("client notifications setup")
                                notificationsSetup.onNext(Optional.of(uuid))
                                obs
                            }
                    }
            }
    }

    private fun <T> connectionError(): Maybe<T> {
        return errorSubject.firstOrError().flatMapMaybe { v -> Maybe.error(v) }
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
        ).toObservable()
            .mergeWith(errorSubject.flatMap { v -> Observable.error(v) })
            .firstOrError()
            .timeout(60, TimeUnit.SECONDS, ioScheduler)
            .doOnError { err -> LOG.w("failed to receive packet ${parser.type} for $luid $err") }
            .doOnSuccess { p -> LOG.e("parsed packet len ${p.bytes.size}") }
    }

    /**
     * reads a single advertise packet
     * @return single emitting advertise packet
     */
    override fun readAdvertise(): Single<AdvertisePacket> {
        return cachedNotification(AdvertisePacketParser.parser)
    }

    /**
     * reads a single upgrade packet
     * @return single emitting upgrade packet
     */
    override fun readUpgrade(): Single<UpgradePacket> {
        return cachedNotification(UpgradePacketParser.parser)
    }

    /**
     * reads a single blockheader packet
     * @return single emitting blockheader packet
     */
    override fun readBlockHeader(): Single<BlockHeaderPacket> {
        return cachedNotification(BlockHeaderPacketParser.parser)
    }

    /**
     * reads a single blocksequence packet
     * @return single emitting blocksequence packet
     */
    override fun readBlockSequence(): Single<BlockSequencePacket> {
        return cachedNotification(BlockSequencePacketParser.parser)
    }

    /**
     * reads a single declarehashes packet
     * @return single emitting delcarehashes packet
     */
    override fun readDeclareHashes(): Single<DeclareHashesPacket> {
        return cachedNotification(DeclareHashesPacketParser.parser)
    }

    /**
     * reads a single electleader packet
     * @return single emitting electleader packet
     */
    override fun readElectLeader(): Single<ElectLeaderPacket> {
        return cachedNotification(ElectLeaderPacketParser.parser)
    }

    /**
     * reads a single identity packet
     * @return single emitting identity packet
     */
    override fun readIdentityPacket(): Single<IdentityPacket> {
        return cachedNotification(IdentityPacketParser.parser)
    }

    /**
     * reads a single luid packet
     * @return single emitting luid packet
     */
    override fun readLuid(): Single<LuidPacket> {
        return cachedNotification(LuidPacketParser.parser)
    }

    /**
     * reads a single ack packet
     * @return single emititng ack packet
     */
    override fun readAck(): Single<AckPacket> {
        return cachedNotification(AckPacketParser.parser)
    }

    /**
     * dispose this connection
     */
    override fun disconnect() {
        LOG.e("CachedLEConnection disposed")
        disposable.dispose()
    }

    /**
     * returns true if this connection is disposed
     */
    fun isDisposed(): Boolean {
        return disposable.isDisposed
    }
}