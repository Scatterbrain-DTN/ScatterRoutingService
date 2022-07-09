package net.ballmerlabs.uscatterbrain.network.bluetoothLE

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
            channelNotifs[id] = InputStreamObserver(4096)
        }
        val disp = premptiveEnable().subscribe()
        disposable.add(disp)
    }

    fun subscribeConnection(rawConnection: Observable<RxBleConnection>) {
        rawConnection.doOnSubscribe { disp ->
            disposable.add(disp)
            LOG.v("subscribed to connection subject")
        }
            .doOnError { err ->
                LOG.e("raw connection error: $err")
                onDisconnect().subscribeOn(scheduler).blockingAwait()
            }
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
     * to avoid data races when writing to the ClientConfig descriptor, 
     * enable indications for all channel characteristics
     * as soon as we have the RxBleConnection.
     * @return Completable
     */
    private fun premptiveEnable(): Completable {
        return Observable.fromIterable(channels.keys)
                .flatMapCompletable{ uuid ->
                    connection
                        .firstOrError()
                        .flatMapCompletable { c ->
                            val subject = channelNotifs[uuid]
                            if (subject != null) {
                                c.setupIndication(uuid, NotificationSetupMode.QUICK_SETUP)
                                        .doOnNext { LOG.v("preemptively enabled indications for $uuid") }
                                        .doOnComplete { LOG.e("indications completed. This is bad") }
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

                }
                .doOnError { e -> LOG.e("failed to preemtively enable indications $e") }
                .onErrorComplete() //We can swallow errors here since indications are enabled already if failed
                .doOnComplete { LOG.v("all notifications enabled")}
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
                    .map{ bytes ->
                        val uuid = BluetoothLERadioModuleImpl.bytes2uuid(bytes)!!
                        if (!channelNotifs.containsKey(uuid)) {
                            throw IllegalStateException("gatt server returned invalid uuid")
                        }
                        uuid
                    }
                }
                .doOnSuccess{ uuid -> LOG.v("client selected channel $uuid") }
    }

    /**
     * select a free channel and read protobuf data from it.
     * use characteristic reads for timing to tell the server we are ready
     * to receive data
     * @return observable emitting bytes received
     */
    private fun cachedNotification(): Single<InputStream> {
        return connection.firstOrError()
            .flatMap { c ->
                selectChannel()
                    .flatMap { uuid ->
                        c.readCharacteristic(uuid)
                            .map {
                                channelNotifs[uuid] as InputStream
                            }
                    }.timeout(BluetoothLEModule.TIMEOUT.toLong(), TimeUnit.SECONDS)
            }

    }

    /**
     * reads a single advertise packet
     * @return single emitting advertise packet
     */
    fun readAdvertise(): Single<AdvertisePacket> {
        return cachedNotification().flatMap { input ->
            ScatterSerializable.parseWrapperFromCRC(AdvertisePacket.parser(), input, scheduler)
                .doOnSubscribe { LOG.v("called readAdvertise") }
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
        }
    }

    /**
     * reads a single upgrade packet
     * @return single emitting upgrade packet
     */
    fun readUpgrade(): Single<UpgradePacket> {
        return cachedNotification().flatMap { input ->
            ScatterSerializable.parseWrapperFromCRC(UpgradePacket.parser(), input, scheduler)
                .doOnSubscribe { LOG.v("called readUpgrade") }
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
        }
    }

    /**
     * reads a single blockheader packet
     * @return single emitting blockheader packet
     */
    fun readBlockHeader(): Single<BlockHeaderPacket> {
        return cachedNotification().flatMap { input ->
            ScatterSerializable.parseWrapperFromCRC(BlockHeaderPacket.parser(), input, scheduler)
                .doOnSubscribe { LOG.v("called readBlockHeader") }
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
        }
    }

    /**
     * reads a single blocksequence packet
     * @return single emitting blocksequence packet
     */
    fun readBlockSequence(): Single<BlockSequencePacket> {
        return cachedNotification().flatMap { input ->
            ScatterSerializable.parseWrapperFromCRC(BlockSequencePacket.parser(), input, scheduler)
                .doOnSubscribe { LOG.v("called readBlockSequence") }
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
        }
    }

    /**
     * reads a single declarehashes packet
     * @return single emitting delcarehashes packet
     */
    fun readDeclareHashes(): Single<DeclareHashesPacket> {
        return cachedNotification().flatMap { input ->
            ScatterSerializable.parseWrapperFromCRC(DeclareHashesPacket.parser(), input, scheduler)
                .doOnSubscribe { LOG.v("called readDeclareHashes") }
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
        }
    }

    /**
     * reads a single electleader packet
     * @return single emitting electleader packet
     */
    fun readElectLeader(): Single<ElectLeaderPacket> {
        return cachedNotification().flatMap { input ->
            ScatterSerializable.parseWrapperFromCRC(ElectLeaderPacket.parser(), input, scheduler)
                .doOnSubscribe { LOG.v("called readElectLeader") }
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
        }
    }

    /**
     * reads a single identity packet
     * @return single emitting identity packet
     */
    fun readIdentityPacket(): Single<IdentityPacket> {
        return cachedNotification().flatMap { input ->
            ScatterSerializable.parseWrapperFromCRC(IdentityPacket.parser(), input, scheduler)
                .doOnSubscribe { LOG.v("called readIdentityPacket") }
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
        }
    }

    /**
     * reads a single luid packet
     * @return single emitting luid packet
     */
    fun readLuid(): Single<LuidPacket> {
        return cachedNotification().flatMap { input ->
            ScatterSerializable.parseWrapperFromCRC(LuidPacket.parser(), input, scheduler)
                .doOnSubscribe { LOG.v("called readLuid") }
                .timeout(timeout, TimeUnit.SECONDS, scheduler)
        }
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