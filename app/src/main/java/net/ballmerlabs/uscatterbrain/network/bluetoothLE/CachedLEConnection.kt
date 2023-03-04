package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGattDescriptor
import com.google.protobuf.MessageLite
import com.polidea.rxandroidble2.NotificationSetupMode
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.Companion.SERVICE_UUID
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.Companion.CLIENT_CONFIG
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
    private val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic>,
    private val scheduler: Scheduler,
    val device: RxBleDevice
) : Disposable {
    private val LOG by scatterLog()
    private val disposable = CompositeDisposable()
    private val timeout: Long = 20

    val connection = BehaviorSubject.create<RxBleConnection>()
    private val disconnectCallbacks = ConcurrentHashMap<() -> Completable, Boolean>()
    private val channelNotifs = ConcurrentHashMap<UUID, InputStreamObserver>()

    init {
        for (id in channels.keys()) {
            val disp = connection.firstOrError()
                .subscribe { c ->
                    c.setupIndication(id, NotificationSetupMode.QUICK_SETUP)
                        .map { obs ->
                            LOG.v("indication setup")
                            val o = InputStreamObserver(4096)
                            channelNotifs[id] = o
                            obs.subscribe(o)
                        }.subscribe()
                }
            disposable.add(disp)
        }
    }

    fun subscribeConnection(rawConnection: Observable<RxBleConnection>) {
        rawConnection.doOnSubscribe { disp ->
            disposable.add(disp)
            LOG.v("subscribed to connection subject")
        }
            .doOnError { err ->
                LOG.e("raw connection error: $err")

            }
            .onErrorResumeNext(Observable.empty())
            .doOnComplete { LOG.e("raw connection completed") }
            .concatWith(onDisconnect())
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
    private fun selectChannel(): Single<UUID> {
        return connection
            .firstOrError()
            .flatMap { c ->
                c.readCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
                    .map { bytes ->
                        val uuid = BluetoothLERadioModuleImpl.bytes2uuid(bytes)!!
                        LOG.v("selected channel $uuid")
                        if (!channelNotifs.containsKey(uuid)) {
                            throw IllegalStateException("gatt server returned invalid uuid")
                        }
                        uuid
                    }
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
                val notif = channelNotifs[uuid]!!
                    ScatterSerializable.parseWrapperFromCRC(
                        parser,
                        notif as InputStream,
                        scheduler
                    ).timeout(BluetoothLEModule.TIMEOUT.toLong(), TimeUnit.SECONDS)
            }
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