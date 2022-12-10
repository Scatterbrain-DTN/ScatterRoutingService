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
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.LockedCharactersitic
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.io.InputStream
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
    private val channels: ConcurrentHashMap<UUID, LockedCharactersitic>,
    private val scheduler: Scheduler,
    val device: RxBleDevice,
    val connection: RxBleConnection
) : Disposable {
    private val LOG by scatterLog()
    private val disposable = CompositeDisposable()
    private val timeout: Long = 20

    private val disconnectCallbacks = ConcurrentHashMap<() -> Completable, Boolean>()
    private val channelNotifs = ConcurrentHashMap<UUID, InputStreamObserver>()

    init {
        for (id in channels.keys()) {
            channelNotifs[id] = InputStreamObserver(4096)
        }
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
            .flatMapCompletable { uuid ->
                val subject = channelNotifs[uuid]
                if (subject != null) {
                    connection.setupIndication(uuid, NotificationSetupMode.QUICK_SETUP)
                        .firstOrError()
                        .doOnError { LOG.e("failed to preemptively enable indications for $uuid") }
                        .flatMapCompletable { obs ->
                            obs
                                .doOnNext { b -> LOG.v("read ${b.size} bytes for channel $uuid") }
                                .doOnError { e -> LOG.e("error in notication obs $e") }
                                .subscribe(subject)
                            Completable.complete()
                        }
                } else {
                    Completable.error(IllegalStateException("channel $uuid does not exist"))
                }


            }
            .onErrorResumeNext { err ->
                when (err) {
                    is BleDisconnectedException -> Completable.error(err)
                    else -> Completable.complete()
                }
            }
            .doOnError { e -> LOG.e("failed to preemtively enable indications $e") }
            .doOnComplete { LOG.v("all notifications enabled") }
    }

    /**
     * read from the semaphor characteristic to determine what channel
     * we are allowed to use
     * @return Single emitting uuid of channel selected
     */
    private fun selectChannel(): Single<UUID> {
        return connection.readCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
            .map { bytes ->
                val uuid = BluetoothLERadioModuleImpl.bytes2uuid(bytes)!!
                if (!channelNotifs.containsKey(uuid)) {
                    throw IllegalStateException("gatt server returned invalid uuid")
                }
                uuid
            }
            .doOnSuccess { uuid -> LOG.v("client selected channel $uuid") }
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
        return selectChannel()
            .flatMap { uuid ->
                connection.setupIndication(uuid, NotificationSetupMode.QUICK_SETUP)
                    .flatMapSingle { obs ->
                        LOG.v("indication setup")
                        val o = InputStreamObserver(4096)
                        obs.subscribe(o)
                        ScatterSerializable.parseWrapperFromCRC(parser, o as InputStream, scheduler)
                    }
                    .firstOrError()
            }
            .timeout(BluetoothLEModule.TIMEOUT.toLong(), TimeUnit.SECONDS)

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