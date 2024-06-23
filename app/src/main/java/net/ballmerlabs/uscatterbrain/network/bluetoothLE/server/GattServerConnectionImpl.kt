package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.*
import android.os.Build
import android.util.Pair
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection.RxBleConnectionState
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Function
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLeServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.Companion.CLIENT_CONFIG
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.LongWriteClosableOutput
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.Output
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerConnectionOperationQueue
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactory
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import kotlin.math.log

@GattServerConnectionScope
class GattServerConnectionImpl @Inject constructor(
    private val serverState: ServerState,
    private val client: RxBleClient,
    private val serverTransactionFactory: ServerTransactionFactory,
    private val firebaseWrapper: FirebaseWrapper,
    private val cachedConnection: Provider<CachedLeServerConnection>,
    private val gattServer: Provider<BluetoothGattServer>,
    private val operationQueue: Provider<ServerConnectionOperationQueue>,
    private val operationProvider: Provider<ServerOperationsProvider>,
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_CALLBACKS) private val callbackScheduler: Scheduler,
) : GattServerConnection {
    private val Log by scatterLog()
    private val compositeDisposable = CompositeDisposable()
    private val currentMtu = ConcurrentHashMap<String, Int>()
    private val addingService = BehaviorSubject.create<Boolean>()
    private val mtuChangedCallback = ConcurrentHashMap<String, (Int) -> Unit>()
    private var onDisconnect: (device: RxBleDevice) -> Unit = {}

    private val deviceOnDisconnect = mutableMapOf<String, () -> Unit>()

    private val characteristicMultiIndex =
        MultiIndex<Int, BluetoothGattCharacteristic, LongWriteClosableOutput<ByteArray>>()
    private val descriptorMultiIndex =
        MultiIndex<Int, BluetoothGattDescriptor, LongWriteClosableOutput<ByteArray>>()

    private val errorMapper: Function<Throwable, Observable<*>> =
        Function { bleGattException -> Observable.error<Any>(bleGattException) }

    private val events = Output<ServerResponseTransaction>()
    private val connectionStatePublishRelay =
        BehaviorRelay.create<Pair<RxBleDevice, RxBleConnectionState>>()
    private val notificationPublishRelay = Output<Pair<String, Int>>()
    private val changedMtuOutput = Output<Pair<String, Int>>()

    private val phyUpdateRelay = PublishRelay.create<Pair<Int, Int>>()

    override fun server(): BluetoothGattServer {
        return gattServer.get()
    }

    private fun registerService(service: BluetoothGattService): Completable {
        return Completable.fromAction {
            addingService.onNext(true)
            for (characteristic in service.characteristics) {
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                    || characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0
                ) {
                    Log.d("setting CLIENT_CONFIG for characteristic " + characteristic.uuid)
                    characteristic.addDescriptor(
                        BluetoothGattDescriptor(
                            CLIENT_CONFIG,
                            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
                        )
                    )
                }
                serverState.addCharacteristic(characteristic.uuid, characteristic)
            }
            try {
                gattServer.get().addService(service)
            } catch (exc: SecurityException) {
                firebaseWrapper.recordException(exc)
                throw exc
            }
        }
    }

    override val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            val rxdevice = client.getBleDevice(device.address)
            connectionStatePublishRelay.accept(
                Pair(
                    rxdevice, mapConnectionStateToRxBleConnectionStatus(newState)
                )
            )
            try {
                if (newState == BluetoothProfile.STATE_DISCONNECTED || newState == BluetoothProfile.STATE_DISCONNECTING) {
                    onDisconnect(rxdevice)
                    mtuChangedCallback.remove(device.address)
                    deviceOnDisconnect[rxdevice.macAddress]?.invoke()
                    deviceOnDisconnect.remove(rxdevice.macAddress)
                    deviceOnDisconnect.remove(rxdevice.macAddress)
                }
            } catch (exc: SecurityException) {
                Log.w("failed to cancelConnection: $exc")
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            super.onServiceAdded(status, service)
            addingService.onNext(false)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            val rxBleDevice: RxBleDevice = client.getBleDevice(device.address)
            if (events.hasObservers()) {
                val transaction = serverTransactionFactory.prepareCharacteristicTransaction(
                    null,
                    requestId,
                    offset,
                    rxBleDevice,
                    characteristic.uuid,
                    characteristic,
                    GattServerConnection.Operation.CHARACTERISTIC_READ
                )
                Log.v("characteristicTransaction")
                events.valueRelay.accept(
                    transaction
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )
            Log.v(
                "onCharacteristicWriteRequest characteristic: " + characteristic.uuid
                        + " device: " + device.address + " responseNeeded " + responseNeeded
            )
            val rxBleDevice = client.getBleDevice(device.address)
             if (events.hasObservers()) {
                val transaction = serverTransactionFactory.prepareCharacteristicTransaction(
                    value,
                    requestId,
                    offset,
                    rxBleDevice,
                    characteristic.uuid,
                    characteristic,
                    GattServerConnection.Operation.CHARACTERISTIC_WRITE
                )
                Log.v("characteristicTransaction")
                events.valueRelay.accept(
                    transaction
                )
            } else {
                Log.e("no observers")
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            Log.v("onDescriptorReadRequest: " + descriptor.uuid)
            val rxBleDevice: RxBleDevice = client.getBleDevice(device.address)
            val disp = if (descriptor.uuid.compareTo(CLIENT_CONFIG) == 0) {
                try {
                    operationQueue.get().queue(
                        operationProvider.get().provideSendResponseOperation(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            serverState.getNotificationValue(descriptor.characteristic.uuid)
                        )
                    ).ignoreElements()
                } catch (exc: SecurityException) {
                    events.errorRelay.accept(exc)
                    return
                }
            } else {
                Completable.complete()
            }.andThen(Observable.fromCallable {
                serverTransactionFactory.prepareCharacteristicTransaction(
                    null,
                    requestId,
                    offset,
                    rxBleDevice,
                    descriptor.uuid,
                    descriptor.characteristic,
                    GattServerConnection.Operation.DESCRIPTOR_READ
                )
            }).doOnError { err -> Log.w("client_config error $err") }

                .onErrorResumeNext(Observable.empty())
                .doOnSubscribe {
                    Log.w("client_config read subscribed")
                }
                .doOnNext { v -> Log.w("transaction: ${v.operation}") }
                .doFinally { Log.w("client_config read completed") }
                .subscribe(events.valueRelay)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onDescriptorWriteRequest(
                device,
                requestId,
                descriptor,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )
            Log.v("onDescriptorWriteRequest: " + descriptor.uuid)
            val rxBleDevice: RxBleDevice = client.getBleDevice(device.address)

            val disp = if (descriptor.uuid.compareTo(CLIENT_CONFIG) == 0) {
                Completable.fromCallable {
                    serverState.setNotifications(descriptor.characteristic.uuid, value)
                }.andThen(
                    operationQueue.get().queue(
                        operationProvider.get().provideSendResponseOperation(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            ByteArray(0)
                        )
                    ).ignoreElements()
                )
            } else {
                Completable.complete()
            }.andThen(Observable.fromCallable {
                serverTransactionFactory.prepareCharacteristicTransaction(
                    value,
                    requestId,
                    offset,
                    rxBleDevice,
                    descriptor.uuid,
                    descriptor.characteristic,
                    GattServerConnection.Operation.DESCRIPTOR_WRITE
                )
            }).doOnError { err -> Log.w("client_config error $err") }

                .onErrorResumeNext(Observable.empty())
                .doOnSubscribe {
                    Log.w("client_config write subscribed")
                }
                .doOnNext { v -> Log.w("transaction: ${v.operation}") }
                .doFinally { Log.w("client_config write completed") }
                .subscribe(events.valueRelay)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            super.onExecuteWrite(device, requestId, execute)
            Log.v("onExecuteWrite $requestId $execute")
            try {
                gattServer.get()
                    .sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
                if (execute) {
                    closeLongWriteCharacteristicOutput(requestId)
                    resetCharacteristicMap()
                    resetDescriptorMap()
                }
            } catch (exc: SecurityException) {
                events.errorRelay.accept(exc)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            if (getNotificationPublishRelay().valueRelay.hasObservers()) {
                Log.v("onNotificationSent: " + device.address + " " + status)
                getNotificationPublishRelay().valueRelay.accept(
                    Pair(device.address, status)
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.e("mtu changed: $mtu")
            forceMtu(device.address, mtu)
            cachedConnection.get().mtu.set(mtu)
            val cb = mtuChangedCallback[device.address]
            if (cb != null) {
                cb(mtu)
            }

            if (changedMtuOutput.valueRelay.hasObservers()) {
                changedMtuOutput.valueRelay.accept(Pair(device.address, mtu))
            }
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(device, txPhy, rxPhy, status)
            Log.e("onPhyUpdate $txPhy $rxPhy $status")
            phyUpdateRelay.accept(Pair(txPhy, rxPhy))
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(device, txPhy, rxPhy, status)
            Log.e("onPhyRead $txPhy $rxPhy $status")
        }
    }

    fun mapConnectionStateToRxBleConnectionStatus(newState: Int): RxBleConnectionState {
        return when (newState) {
            BluetoothProfile.STATE_CONNECTING -> RxBleConnectionState.CONNECTING
            BluetoothProfile.STATE_CONNECTED -> RxBleConnectionState.CONNECTED
            BluetoothProfile.STATE_DISCONNECTING -> RxBleConnectionState.DISCONNECTING
            else -> RxBleConnectionState.DISCONNECTED
        }
    }


    override fun setOnMtuChanged(device: BluetoothDevice, callback: (Int) -> Unit) {
        mtuChangedCallback[device.address] = callback
    }


    /**
     * @return Observable that emits RxBleConnectionState that matches BluetoothGatt's state.
     * Does NOT emit errors even if status != GATT_SUCCESS.
     */
    override fun getOnConnectionStateChange(): Observable<Pair<RxBleDevice, RxBleConnectionState>> {
        return connectionStatePublishRelay
    }


    override fun awaitPhyUpdate(): Observable<Pair<Int, Int>> {
        return phyUpdateRelay
    }


    override fun initializeServer(config: ServerConfig): Completable {
        for (phy in config.getPhySet()) {
            when (phy) {
                ServerConfig.BluetoothPhy.PHY_LE_1M, ServerConfig.BluetoothPhy.PHY_LE_2M, ServerConfig.BluetoothPhy.PHY_LE_CODED -> {}
            }
        }
        return Observable.fromIterable(config.getServices().values)
            .concatMapCompletable { service ->
                registerService(service).andThen(addingService.takeUntil { v -> !v }
                    .ignoreElements())
            }
    }

    fun getNotificationPublishRelay(): Output<Pair<String, Int>> {
        return notificationPublishRelay
    }

    override fun observeOnMtuChanged(device: BluetoothDevice): Observable<Int> {
        return withDisconnectionHandling(changedMtuOutput)
            .filter { v -> device.address == v.first }
            .map { v -> v.second }
    }

    fun closeLongWriteCharacteristicOutput(requestid: Int): Single<ByteArray> {
        return Single.just(requestid)
            .flatMap { integer ->
                val output = characteristicMultiIndex[integer]
                if (output != null) {
                    characteristicMultiIndex.remove(integer)
                    output.out
                }
                Single.never()
            }
    }

    fun closeLongWriteDescriptorOutput(requestid: Int): Single<ByteArray> {
        return Single.just(requestid)
            .flatMap { integer ->
                val output = descriptorMultiIndex[integer]
                if (output != null) {
                    characteristicMultiIndex.remove(integer)
                    output.out
                }
                Single.never()
            }
    }

    override fun getMtu(address: String): Int {
        return currentMtu[address] ?: 20
    }

    override fun resetMtu(address: String) {
        currentMtu.remove(address)
    }

    override fun clearMtu() {
        currentMtu.clear()
    }

    fun resetDescriptorMap() {
        descriptorMultiIndex.clear()
    }

    fun resetCharacteristicMap() {
        characteristicMultiIndex.clear()
    }

    override fun setupIndication(
        ch: UUID,
        indications: Flowable<ByteArray>,
        device: RxBleDevice
    ): Completable {
        return Single.fromCallable {
            val characteristic = serverState.getCharacteristic(ch)
            setupNotifications(characteristic, indications, true, device)
        }.flatMapCompletable { completable -> completable.ignoreElements() }
    }

    private fun setupNotificationsDelay(
        clientconfig: BluetoothGattDescriptor,
        characteristic: BluetoothGattCharacteristic,
        isIndication: Boolean,
        mac: String
    ): Completable {
        return Single.fromCallable {
            if (isIndication && serverState.getIndications(characteristic.uuid)) {
                Log.v("immediate start indication")
                Completable.complete()
            } else if (serverState.getNotifications(characteristic.uuid)) {
                Log.v("immediate start notification")
                Completable.complete()
            } else {
                getEvents()
                    .filter { v -> v.remoteDevice.macAddress == mac }
                    .filter { e -> e.operation == GattServerConnection.Operation.DESCRIPTOR_WRITE }
                    .filter { transaction ->
                        (transaction.uuid.compareTo(clientconfig.uuid) == 0
                                && transaction.characteristic.uuid
                            .compareTo(clientconfig.characteristic.uuid) == 0)
                    }
                    .takeWhile { trans ->
                        trans.value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                    }
                    .ignoreElements()
            }
        }.flatMapCompletable { completable -> completable }
    }

    override fun setupNotifications(
        ch: UUID,
        notifications: Flowable<ByteArray>,
        device: RxBleDevice
    ): Completable {
        return Single.fromCallable {
            val characteristic = serverState.getCharacteristic(ch)
            setupNotifications(characteristic, notifications, false, device)
        }.flatMapCompletable { completable -> completable.ignoreElements() }
    }

    override fun setupNotifications(
        characteristic: BluetoothGattCharacteristic,
        notifications: Flowable<ByteArray>,
        isIndication: Boolean,
        device: RxBleDevice
    ): Flowable<ByteArray> {
        return Flowable.defer {
            Log.v("setupNotifictions: " + characteristic.uuid)
            val clientconfig = characteristic.getDescriptor(CLIENT_CONFIG)
                ?: return@defer Flowable.error(IllegalStateException("notification failed"))

            notifications
                .delay<ByteArray> {
                    setupNotificationsDelay(
                        clientconfig,
                        characteristic,
                        isIndication,
                        device.macAddress
                    ).toFlowable()
                }
                .zipWith(
                    Observable.just(BluetoothGatt.GATT_SUCCESS)
                        .concatWith(getOnNotification(device.macAddress))
                        .toFlowable(BackpressureStrategy.BUFFER)
                ) { bytes, v ->
                    if (v != BluetoothGatt.GATT_SUCCESS)
                        throw IllegalStateException("notification failure: $v")
                    bytes
                }
                .concatMapSingle { bytes ->
                    Log.v("processing bytes ${bytes.size}")
                    operationQueue.get().queue(
                        operationProvider.get().provideNotifyCharacteristicChangedOperation(
                            device.bluetoothDevice,
                            characteristic,
                            isIndication,
                            bytes
                        )
                    ).flatMapSingle { v ->
                        if (v == BluetoothGatt.GATT_SUCCESS) {
                            Single.just(bytes)
                        } else {
                            Single.error(IllegalStateException("gatt returned $v"))
                        }
                    }.firstOrError()
                }

        }
    }

    private fun <T> withDisconnectionHandling(output: Output<T>): Observable<T> {
        return Observable.merge(
            output.valueRelay,
            output.errorRelay.flatMap(errorMapper) as Observable<T>
        )
    }

    override fun observeDisconnect(): Observable<RxBleDevice> {
        return connectionStatePublishRelay
            .filter { pair -> pair.second == RxBleConnectionState.DISCONNECTED }
            .map { pair -> pair.first }
    }

    override fun observeConnect(): Observable<RxBleDevice> {
        return connectionStatePublishRelay
            .filter { pair -> pair.second == RxBleConnectionState.CONNECTED }
            .map { pair -> pair.first }
    }

    override fun forceMtu(address: String, mtu: Int) {
        currentMtu.compute(address) { _, m ->
            if (m == null)
                mtu
            else if (mtu >= m)
                m
            else
                mtu
        }
    }

    override fun getEvents(): Observable<ServerResponseTransaction> {
        return withDisconnectionHandling(events)
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun getOnNotification(mac: String): Observable<Int> {
        return withDisconnectionHandling(notificationPublishRelay)
            .filter { p -> p.first == mac }
            .map { p -> p.second }
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun disconnect(device: RxBleDevice): Completable {
        return operationQueue.get()
            .queue(
                operationProvider.get()
                    .provideDisconnectOperation(device.bluetoothDevice)
            )
            .ignoreElements()
    }

    override fun setOnDisconnect(func: (device: RxBleDevice) -> Unit) {
        onDisconnect = func
    }

    override fun setOnDisconnect(device: String, func: () -> Unit) {
        deviceOnDisconnect[device] = func
    }

    override fun blindAck(
        requestID: Int,
        status: Int,
        value: ByteArray,
        device: RxBleDevice
    ): Observable<Boolean> {
        return operationQueue.get().queue(
            operationProvider.get().provideSendResponseOperation(
                device.bluetoothDevice,
                requestID,
                status,
                value
            )
        ).map { true }
            .onErrorReturnItem(false)
    }

    override fun dispose() {
        Log.e("gatt server disposed")
        try {
            gattServer.get()?.close()
        } catch (exc: SecurityException) {
            firebaseWrapper.recordException(exc)
        }
        compositeDisposable.dispose()
    }

    protected fun finalize() {
        dispose()
    }

    override fun isDisposed(): Boolean {
        return compositeDisposable.isDisposed
    }

    init {
        addingService.onNext(false)
    }

}