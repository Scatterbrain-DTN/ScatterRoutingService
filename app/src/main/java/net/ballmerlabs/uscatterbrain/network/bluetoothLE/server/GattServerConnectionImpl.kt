package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.*
import android.util.Pair
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection.RxBleConnectionState
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Function
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.Companion.CLIENT_CONFIG
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.LongWriteClosableOutput
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.Output
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.GattServerOperationQueue
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.GattServerTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactory
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

@GattServerConnectionScope
class GattServerConnectionImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val connectionScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_CALLBACKS) private val callbackScheduler: Scheduler,
    private val operationQueue: GattServerOperationQueue,
    private val serverState: ServerState,
    private val client: RxBleClient,
    private val bluetoothManager: BluetoothManager,
    private val operationsProvider: GattServerConnectionOperationsProvider,
    private val serverTransactionFactory: ServerTransactionFactory,
    private val firebaseWrapper: FirebaseWrapper,
    private val gattServer: Provider<BluetoothGattServer>
) : GattServerConnection {
    private val Log by scatterLog()
    private val compositeDisposable = CompositeDisposable()

    private var onDisconnect: (device: RxBleDevice) -> Unit = {}

    private val deviceOnDisconnect = mutableMapOf<RxBleDevice, () -> Unit>()

    private val characteristicMultiIndex =
        MultiIndex<Int, BluetoothGattCharacteristic, LongWriteClosableOutput<ByteArray>>()
    private val descriptorMultiIndex =
        MultiIndex<Int, BluetoothGattDescriptor, LongWriteClosableOutput<ByteArray>>()

    private val errorMapper: Function<Throwable, Observable<*>> =
        Function { bleGattException -> Observable.error<Any>(bleGattException) }

    private val readCharacteristicOutput = Output<GattServerTransaction<UUID>>()
    private val writeCharacteristicOutput = Output<GattServerTransaction<UUID>>()
    private val readDescriptorOutput = Output<GattServerTransaction<BluetoothGattDescriptor>>()
    private val writeDescriptorOutput = Output<GattServerTransaction<BluetoothGattDescriptor>>()
    private val connectionStatePublishRelay =
        PublishRelay.create<Pair<RxBleDevice, RxBleConnectionState>>()
    private val notificationPublishRelay = Output<Int>()
    private val changedMtuOutput = Output<Int>()
    private fun registerService(service: BluetoothGattService): Completable {
        return Completable.fromAction {
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
            Log.d("gatt server onConnectionStateChange: " + device.address + " " + status + " " + newState)
            val rxdevice = client.getBleDevice(device.address)
            connectionStatePublishRelay.accept(
                Pair(
                    rxdevice, mapConnectionStateToRxBleConnectionStatus(newState)
                )
            )
            if (newState == BluetoothGattServer.STATE_DISCONNECTING ||
                newState == BluetoothGattServer.STATE_DISCONNECTED
            ) {
                onDisconnect(rxdevice)
                deviceOnDisconnect[rxdevice]?.invoke()
                deviceOnDisconnect.remove(rxdevice)
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            super.onServiceAdded(status, service)
            //TODO:
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            val rxBleDevice: RxBleDevice = client.getBleDevice(device.address)
            if (getReadCharacteristicOutput().hasObservers()) {
                prepareCharacteristicTransaction(
                    characteristic,
                    requestId,
                    offset,
                    rxBleDevice,
                    getReadCharacteristicOutput().valueRelay,
                    null
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
            if (preparedWrite) {
                Log.v("characteristic long write")
                val longWriteOutput = openLongWriteCharacteristicOutput(requestId, characteristic)
                if (responseNeeded) {
                    try {
                        gattServer.get().sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            ByteArray(0)
                        )
                    } catch (exc: SecurityException) {
                        getWriteCharacteristicOutput().errorRelay.onNext(exc)
                    }
                }
                longWriteOutput.valueRelay.onNext(value)
            } else if (getWriteCharacteristicOutput().hasObservers()) {
                prepareCharacteristicTransaction(
                    characteristic,
                    requestId,
                    offset,
                    rxBleDevice,
                    getWriteCharacteristicOutput().valueRelay,
                    value
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
            if (descriptor.uuid.compareTo(CLIENT_CONFIG) == 0) {
                try {
                    gattServer.get().sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        serverState.getNotificationValue(descriptor.characteristic.uuid)
                    )
                } catch (exc: SecurityException) {
                    getReadCharacteristicOutput().errorRelay.onNext(exc)
                }
            }
            if (getReadDescriptorOutput().hasObservers()) {
                prepareDescriptorTransaction(
                    descriptor,
                    requestId,
                    offset,
                    rxBleDevice,
                    getReadDescriptorOutput().valueRelay,
                    null
                )
            } else {
                Log.e("no observers")
            }
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
            try {
                if (preparedWrite) {
                    Log.v("onDescriptorWriteRequest: invoking preparedWrite")
                    val longWriteOutput: Output<ByteArray> =
                        openLongWriteDescriptorOutput(requestId, descriptor)
                    if (responseNeeded) {
                        gattServer.get().sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            ByteArray(0)
                        )
                    }
                    longWriteOutput.valueRelay.onNext(value) //TODO: offset
                } else {
                    if (descriptor.uuid.compareTo(CLIENT_CONFIG) == 0) {
                        serverState.setNotifications(descriptor.characteristic.uuid, value)
                        gattServer.get().sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            ByteArray(0)
                        )
                    }
                    if (writeDescriptorOutput.hasObservers()) {
                        prepareDescriptorTransaction(
                            descriptor,
                            requestId,
                            offset,
                            rxBleDevice,
                            writeDescriptorOutput.valueRelay,
                            value
                        )
                    } else {
                        Log.e("no observers")
                    }
                }
            } catch (exc: SecurityException) {
                getWriteDescriptorOutput().errorRelay.onNext(exc)
            }
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
                getWriteCharacteristicOutput().errorRelay.onNext(exc)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            if (getNotificationPublishRelay().valueRelay.hasObservers()) {
                Log.v("onNotificationSent: " + device.address + " " + status)
                getNotificationPublishRelay().valueRelay.onNext(
                    status
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            super.onMtuChanged(device, mtu)
            if (getChangedMtuOutput().valueRelay.hasObservers()) {
                getChangedMtuOutput().valueRelay.onNext(mtu)
            }
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(device, txPhy, rxPhy, status)
            //TODO: handle phy change
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(device, txPhy, rxPhy, status)
            //TODO: handle phy read
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

    /**
     * @return Observable that emits RxBleConnectionState that matches BluetoothGatt's state.
     * Does NOT emit errors even if status != GATT_SUCCESS.
     */
    override fun getOnConnectionStateChange(): Observable<Pair<RxBleDevice, RxBleConnectionState>> {
        return connectionStatePublishRelay.delay(0, TimeUnit.SECONDS, callbackScheduler)
    }


    override fun initializeServer(config: ServerConfig): Completable {
        for (phy in config.getPhySet()) {
            when (phy) {
                ServerConfig.BluetoothPhy.PHY_LE_1M, ServerConfig.BluetoothPhy.PHY_LE_2M, ServerConfig.BluetoothPhy.PHY_LE_CODED -> {}
            }
        }
        return Observable.fromIterable(config.getServices().values).flatMapCompletable { service ->
            registerService(service)
        }
    }

    override fun getReadCharacteristicOutput(): Output<GattServerTransaction<UUID>> {
        return readCharacteristicOutput
    }

    override fun getWriteCharacteristicOutput(): Output<GattServerTransaction<UUID>> {
        return writeCharacteristicOutput
    }

    override fun getReadDescriptorOutput(): Output<GattServerTransaction<BluetoothGattDescriptor>> {
        return readDescriptorOutput
    }

    override fun getWriteDescriptorOutput(): Output<GattServerTransaction<BluetoothGattDescriptor>> {
        return writeDescriptorOutput
    }

    override fun getNotificationPublishRelay(): Output<Int> {
        return notificationPublishRelay
    }

    override fun getChangedMtuOutput(): Output<Int> {
        return changedMtuOutput
    }

    override fun openLongWriteCharacteristicOutput(
        requestid: Int,
        characteristic: BluetoothGattCharacteristic
    ): LongWriteClosableOutput<ByteArray> {
        var output = characteristicMultiIndex[requestid]
        if (output == null) {
            output = LongWriteClosableOutput()
            output.valueRelay
                .reduce { first, second ->
                    val both = first.copyOf(first.size + second.size)
                    System.arraycopy(second, 0, both, first.size, second.size)
                    both
                }
                .subscribeOn(connectionScheduler)
                .toSingle()
                .subscribe(output.out)
            characteristicMultiIndex.put(requestid, output)
            characteristicMultiIndex.putMulti(characteristic, output)
        }
        return output
    }

    override fun openLongWriteDescriptorOutput(
        requestid: Int,
        descriptor: BluetoothGattDescriptor
    ): LongWriteClosableOutput<ByteArray> {
        var output = descriptorMultiIndex[requestid]
        if (output == null) {
            output = LongWriteClosableOutput()
            output.valueRelay
                .reduce { first, second ->
                    val both = first.copyOf(first.size + second.size)
                    System.arraycopy(second, 0, both, first.size, second.size)
                    both
                }
                .subscribeOn(connectionScheduler)
                .toSingle()
                .subscribe(output.out)
            descriptorMultiIndex.put(requestid, output)
            descriptorMultiIndex.putMulti(descriptor, output)
        }
        return output
    }

    override fun closeLongWriteCharacteristicOutput(requestid: Int): Single<ByteArray> {
        return Single.just(requestid)
            .flatMap(Function<Int, SingleSource<out ByteArray>> { integer ->
                val output = characteristicMultiIndex.get(integer)
                if (output != null) {
                    output.valueRelay.onComplete()
                    characteristicMultiIndex.remove(integer)
                    return@Function output.out.delay(0, TimeUnit.SECONDS, callbackScheduler)
                }
                Single.never()
            })
    }

    override fun closeLongWriteDescriptorOutput(requestid: Int): Single<ByteArray> {
        return Single.just(requestid)
            .flatMap(Function<Int, SingleSource<out ByteArray>> { integer ->
                val output = descriptorMultiIndex[integer]
                if (output != null) {
                    output.valueRelay.onComplete()
                    characteristicMultiIndex.remove(integer)
                    return@Function output.out.delay(0, TimeUnit.SECONDS, connectionScheduler)
                }
                Single.never()
            })
    }

    override fun resetDescriptorMap() {
        descriptorMultiIndex.clear()
    }

    override fun resetCharacteristicMap() {
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
        }.flatMapCompletable { completable -> completable }
    }

    private fun setupNotificationsDelay(
        clientconfig: BluetoothGattDescriptor,
        characteristic: BluetoothGattCharacteristic,
        isIndication: Boolean
    ): Completable {
        return Single.fromCallable {
            if (isIndication && serverState.getIndications(characteristic.uuid)) {
                Log.v("immediate start indication")
                Completable.complete()
            } else if (serverState.getNotifications(characteristic.uuid)) {
                Log.v("immediate start notification")
                Completable.complete()
            } else {
                withDisconnectionHandling(getWriteDescriptorOutput())
                    .filter { transaction ->
                        (transaction.first.uuid.compareTo(clientconfig.uuid) == 0
                                && transaction.first.characteristic.uuid
                            .compareTo(clientconfig.characteristic.uuid) == 0)
                    }
                    .takeWhile { trans ->
                        Arrays.equals(
                            trans.second.value,
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        )
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
        }.flatMapCompletable { completable -> completable }
    }

    override fun setupNotifications(
        characteristic: BluetoothGattCharacteristic,
        notifications: Flowable<ByteArray>,
        isIndication: Boolean,
        device: RxBleDevice
    ): Completable {
        return Completable.defer(Callable {
            Log.v("setupNotifictions: " + characteristic.uuid)
            val clientconfig = characteristic.getDescriptor(CLIENT_CONFIG)
                ?: return@Callable Completable.error(IllegalStateException("notification failed"))
            notifications
                .delay<ByteArray> {
                    setupNotificationsDelay(clientconfig, characteristic, isIndication)
                        .timeout(5, TimeUnit.SECONDS)
                        .toFlowable()
                }
                .onBackpressureBuffer()
                .concatMap { bytes ->
                    Log.v("processing bytes length: " + bytes.size)
                    try {
                        if (bluetoothManager.getConnectionState(
                                device.bluetoothDevice,
                                BluetoothProfile.GATT_SERVER
                            )
                            == BluetoothProfile.STATE_CONNECTED
                        ) {
                            val operation = operationsProvider.provideNotifyOperation(
                                characteristic,
                                bytes,
                                isIndication,
                                device
                            )
                            Log.v("queueing notification/indication")
                            operationQueue.queue(operation).toFlowable(BackpressureStrategy.BUFFER)
                        } else {
                            Flowable.error(IllegalStateException("not connected"))
                        }
                    } catch (exc: SecurityException) {
                        firebaseWrapper.recordException(exc)
                        Flowable.error(exc)
                    }
                }
                .concatMap { integer ->
                    Log.v("notification result: $integer")
                    if (integer != BluetoothGatt.GATT_SUCCESS) {
                        Flowable.error(IllegalStateException("notification failed $integer"))
                    } else {
                        Flowable.just(integer)
                    }
                }
                .ignoreElements()
                .doOnComplete { Log.v("notifications completed!") }
        })
    }

    private fun <T> withDisconnectionHandling(output: Output<T>): Observable<T> {
        return Observable.merge(
            output.valueRelay,
            output.errorRelay.flatMap(errorMapper) as Observable<T>
        )
    }

    override fun getOnMtuChanged(): Observable<Int> {
        return withDisconnectionHandling(getChangedMtuOutput())
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun observeDisconnect(): Observable<RxBleDevice> {
        return connectionStatePublishRelay
            .filter { pair -> pair.second == RxBleConnectionState.DISCONNECTED }
            .map { pair -> pair.first }
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun observeConnect(): Observable<RxBleDevice> {
        return connectionStatePublishRelay
            .filter { pair -> pair.second == RxBleConnectionState.CONNECTED }
            .map { pair -> pair.first }
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun getOnCharacteristicReadRequest(characteristic: UUID): Observable<ServerResponseTransaction> {
        return withDisconnectionHandling(getReadCharacteristicOutput())
            .filter { uuidGattServerTransaction ->
                uuidGattServerTransaction.first.compareTo(
                    characteristic
                ) == 0
            }
            .map { uuidGattServerTransaction -> uuidGattServerTransaction.second }
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }


    override fun getOnCharacteristicWriteRequest(characteristic: UUID): Observable<ServerResponseTransaction> {
        return withDisconnectionHandling(getWriteCharacteristicOutput())
            .filter { uuidGattServerTransaction ->
                uuidGattServerTransaction.first.compareTo(
                    characteristic
                ) == 0
            }
            .map { uuidGattServerTransaction -> uuidGattServerTransaction.second }
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun getOnDescriptorReadRequest(
        characteristic: UUID,
        descriptor: UUID
    ): Observable<ServerResponseTransaction> {
        return withDisconnectionHandling(getReadDescriptorOutput())
            .filter { transaction ->
                (transaction.first.uuid.compareTo(descriptor) == 0
                        && transaction.first.characteristic.uuid
                    .compareTo(characteristic) == 0)
            }
            .map { transaction -> transaction.second }
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun getOnDescriptorWriteRequest(
        characteristic: UUID,
        descriptor: UUID
    ): Observable<ServerResponseTransaction> {
        return withDisconnectionHandling(getWriteDescriptorOutput())
            .filter { transaction ->
                (transaction.first.uuid.compareTo(descriptor) == 0
                        && transaction.first.characteristic.uuid
                    .compareTo(characteristic) == 0)
            }
            .map { transaction -> transaction.second }
            .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun getOnNotification(): Observable<Int> {
        return notificationPublishRelay.valueRelay
    }

    override fun disconnect(device: RxBleDevice): Completable {
        return operationQueue.queue<Void>(operationsProvider.provideDisconnectOperation(device))
            .ignoreElements()
    }

    override fun setOnDisconnect(func: (device: RxBleDevice) -> Unit) {
        onDisconnect = func
    }

    override fun setOnDisconnect(device: RxBleDevice, func: () -> Unit) {
        deviceOnDisconnect[device] = func
    }

    override fun prepareDescriptorTransaction(
        descriptor: BluetoothGattDescriptor,
        requestID: Int,
        offset: Int, device: RxBleDevice,
        valueRelay: PublishSubject<GattServerTransaction<BluetoothGattDescriptor>>,
        value: ByteArray?
    ) {
        val transaction = serverTransactionFactory.prepareCharacteristicTransaction(
            value,
            requestID,
            offset,
            device,
            descriptor.uuid
        )
        valueRelay.onNext(GattServerTransaction(descriptor, transaction))
    }

    override fun prepareCharacteristicTransaction(
        descriptor: BluetoothGattCharacteristic,
        requestID: Int,
        offset: Int,
        device: RxBleDevice,
        valueRelay: PublishSubject<GattServerTransaction<UUID>>,
        value: ByteArray?
    ) {
        val transaction = serverTransactionFactory.prepareCharacteristicTransaction(
            value,
            requestID,
            offset,
            device,
            descriptor.uuid
        )
        Log.v("characteristicTransaction")
        valueRelay.onNext(GattServerTransaction(descriptor.uuid, transaction))
    }

    override fun blindAck(
        requestID: Int,
        status: Int,
        value: ByteArray,
        device: RxBleDevice
    ): Observable<Boolean> {
        return operationQueue.queue<Boolean>(
            operationsProvider.provideReplyOperation(
                device,
                requestID,
                status,
                0,
                value
            )
        )
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

    override fun isDisposed(): Boolean {
        return compositeDisposable.isDisposed
    }

    init {

    }

}