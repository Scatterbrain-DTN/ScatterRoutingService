package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.Timeout
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerConfig
import net.ballmerlabs.uscatterbrain.network.getHashUuid
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ManagedGattServerImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val serverScheduler: Scheduler,
    private val newServer: GattServer,
    @Named(RoutingServiceComponent.NamedSchedulers.IO) private val operationsScheduler: Scheduler,
    private val advertiser: Advertiser,
    private val state: LeState,
    private val builder: ScatterbrainTransactionSubcomponent.Builder,
    private val firebase: FirebaseWrapper
) : ManagedGattServer {

    private val server = AtomicReference<Pair<CachedLEServerConnection, Disposable>?>(null)

    private val LOG by scatterLog()


    private fun helloRead(serverConnection: GattServerConnection): Completable {
        return serverConnection.getOnCharacteristicReadRequest(BluetoothLERadioModuleImpl.UUID_HELLO)
            .subscribeOn(operationsScheduler)
            .doOnSubscribe { LOG.v("hello characteristic read subscribed") }
            .flatMapCompletable { trans ->
                val luid = getHashUuid(advertiser.myLuid.get())
                LOG.v("hello characteristic read, replying with luid $luid")
                trans.sendReply(
                    BluetoothLERadioModuleImpl.uuid2bytes(luid),
                    BluetoothGatt.GATT_SUCCESS
                )
            }
            .doOnError { err ->
                LOG.e("error in hello characteristic read: $err")
            }.onErrorComplete()
    }

    override fun disconnect(device: RxBleDevice?) {
        if (device != null) {
            server.get()?.first?.connection?.disconnect(device)
        }
    }

    override fun stopServer() {
        val s = server.getAndSet(null)
        s?.first?.dispose()
        s?.second?.dispose()
    }

    private fun helloWrite(serverConnection: GattServerConnection): Observable<HandshakeResult> {
        return serverConnection.getOnCharacteristicWriteRequest(BluetoothLERadioModuleImpl.UUID_HELLO)
            .subscribeOn(operationsScheduler)
            .flatMapMaybe { trans ->
                LOG.e("hello from ${trans.remoteDevice.macAddress}")
                val luid = BluetoothLERadioModuleImpl.bytes2uuid(trans.value)!!

                if(state.transactionLockIsSelf(luid)) {
                    serverConnection.setOnDisconnect(trans.remoteDevice) {
                        LOG.e("server onDisconnect $luid")
                        state.updateDisconnected(luid)
                        if (state.connectionCache.isEmpty()) {
                            advertiser.removeLuid().blockingAwait()
                        }
                    }
                    LOG.e("server handling luid $luid")
                    LOG.e("transaction NOT locked, continuing")
                    trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                        .andThen(state.establishConnectionCached(trans.remoteDevice, luid))
                        .flatMapMaybe { connection ->
                            val t = builder.build()!!.bluetoothLeRadioModule()
                            t.handleConnection(
                                connection,
                                trans.remoteDevice,
                                luid
                            )
                        }
                        .doOnError { err ->
                            LOG.e("error in handleConnection $err")
                            firebase.recordException(err)
                            state.updateDisconnected(luid)
                        }
                } else {
                    trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_FAILURE)
                        .toMaybe()
                }

            }
            .onErrorReturnItem(
                HandshakeResult(
                    0,
                    0,
                    HandshakeResult.TransactionStatus.STATUS_FAIL
                )
            )
            .doOnError { e ->
                LOG.e("failed to read hello characteristic: $e")
            }
            .doOnNext { t -> LOG.v("transactionResult ${t.success}") }
    }

    /**
     * starts the gatt server in the background.
     * NOTE: this function contains all the LOGic for running the state machine.
     * this function NEEDS to be called for the device to be connectable
     * @return false on failure
     */
    override fun startServer(): Completable {
        // initialize our channels
        makeCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
        makeCharacteristic(BluetoothLERadioModuleImpl.UUID_HELLO)
        for (i in 0 until BluetoothLERadioModuleImpl.NUM_CHANNELS) {
            val channel = BluetoothLERadioModuleImpl.incrementUUID(
                BluetoothLERadioModuleImpl.SERVICE_UUID,
                i + 1
            )
            state.channels[channel] =
                BluetoothLERadioModuleImpl.LockedCharactersitic(makeCharacteristic(channel), i)
        }
        val config = ServerConfig(operationTimeout = Timeout(5, TimeUnit.SECONDS))
            .addService(gattService)

        /*
         * NOTE: HIGHLY IMPORTANT: ACHTUNG!!
         * this may seem like black magic, but gatt server connections are registered for
         * BOTH incoming gatt connections AND outgoing connections. In fact, I cannot find a way to
         * distinguish incoming and outgoing connections. So every connection, even CLIENT connections
         * that we just initiated show up as emissions from this observable. Really wonky right?
         *
         * In a perfect world I would refactor my fork of RxAndroidBle to fix this, but the changes
         * required to do that are very invasive and probably not worth it in the long run.
         *
         * As a result, gatt client connections are seemingly thrown away and unhandled. THIS IS FAKE NEWS
         * they are handled here.
         */
        return newServer.openServer(config)
            .subscribeOn(serverScheduler)
            .doOnSubscribe { LOG.v("gatt server subscribed") }
            .doOnError { e ->
                LOG.e("failed to open server: $e")
            }
            .flatMapCompletable { connectionRaw ->
                Completable.fromAction {
                    LOG.v("gatt server initialized")
                    val s = CachedLEServerConnection(
                        connectionRaw,
                        state.channels,
                        scheduler = operationsScheduler,
                        ioScheduler = operationsScheduler,
                        firebaseWrapper = firebase
                    )

                    val write = helloWrite(connectionRaw)
                    val read = helloRead(connectionRaw)

                    val disp = write.mergeWith(read)
                        .retry(10)
                        .ignoreElements()
                        .subscribe(
                            { LOG.e("server handler completed") },
                            { err -> LOG.e("server handler error $err") }
                        )

                    val old = server.getAndSet(Pair(s, disp))
                    old?.first?.dispose()
                    old?.second?.dispose()
                }
            }
            .cache()
    }

    override fun getServer(): Maybe<CachedLEServerConnection> {
        return Maybe.defer {
            val s = server.get()
            if (s != null) {
                Maybe.just(s.first)
            } else {
                Maybe.empty()
            }
        }
    }
}