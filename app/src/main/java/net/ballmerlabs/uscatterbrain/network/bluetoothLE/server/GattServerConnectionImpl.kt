package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.*
import android.content.Context
import android.util.Log
import android.util.Pair
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.*
import com.polidea.rxandroidble2.RxBleConnection.RxBleConnectionState
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.exceptions.BleGattServerException
import com.polidea.rxandroidble2.exceptions.BleGattServerOperationType
import com.polidea.rxandroidble2.internal.RxBleLog
import com.polidea.rxandroidble2.internal.serialization.ServerOperationQueue
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.Output
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.LongWriteClosableOutput
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Function
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.GattServerTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactory
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

@GattServerConnectionScope
class GattServerConnectionImpl @Inject constructor(
        private val config: ServerConfig,
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val connectionScheduler: Scheduler,
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_CALLBACKS) private val callbackScheduler: Scheduler,
        private val operationQueue: ServerOperationQueue,
        private val context: Context,
        private val serverState: ServerState,
        private val client: RxBleClient,
        private val bluetoothManager: BluetoothManager,
        private val operationsProvider: GattServerConnectionOperationsProvider,
        private val serverTransactionFactory: ServerTransactionFactory
) : GattServerConnection {
    private val compositeDisposable = CompositeDisposable()
    
    private lateinit var gattServer: BluetoothGattServer

    private val characteristicMultiIndex = MultiIndex<Int, BluetoothGattCharacteristic, GattServerConnection.LongWriteClosableOutput<ByteArray>>()
    private val descriptorMultiIndex = MultiIndex<Int, BluetoothGattDescriptor, GattServerConnection.LongWriteClosableOutput<ByteArray>>()
    
    private val errorMapper: Function<BleException, Observable<*>> = Function { bleGattException -> Observable.error<Any>(bleGattException) }

    private val readCharacteristicOutput = Output<GattServerTransaction<UUID>>()
    private val writeCharacteristicOutput = Output<GattServerTransaction<UUID>>()
    private val readDescriptorOutput = Output<GattServerTransaction<BluetoothGattDescriptor>>()
    private val writeDescriptorOutput = Output<GattServerTransaction<BluetoothGattDescriptor>>()
    private val connectionStatePublishRelay = PublishRelay.create<Pair<RxBleDevice, RxBleConnectionState>>()
    private val notificationPublishRelay = Output<Int>()
    private val changedMtuOutput = Output<Int>()
    fun registerService(service: BluetoothGattService) {
        for (characteristic in service.characteristics) {
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                    || characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) {
                RxBleLog.d("setting CLIENT_CONFIG for characteristic " + characteristic.uuid)
                characteristic.addDescriptor(BluetoothGattDescriptor(
                        RxBleClient.CLIENT_CONFIG,
                        BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
                ))
            }
            serverState.addCharacteristic(characteristic.uuid, characteristic)
        }
        gattServer.addService(service)
    }

    private val gattServerCallback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            RxBleLog.d("gatt server onConnectionStateChange: " + device.address + " " + status + " " + newState)
            if (newState == BluetoothProfile.STATE_DISCONNECTED
                    || newState == BluetoothProfile.STATE_DISCONNECTING) {
                //TODO:
                RxBleLog.e("device " + device.address + " disconnecting")
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                //TODO:
                RxBleLog.e("GattServer state change failed %i", status)
            }
            connectionStatePublishRelay.accept(Pair(
                    client.getBleDevice(device.address), mapConnectionStateToRxBleConnectionStatus(newState)
            ))
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            super.onServiceAdded(status, service)
            //TODO:
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice,
                                                 requestId: Int,
                                                 offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
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

        override fun onCharacteristicWriteRequest(device: BluetoothDevice,
                                                  requestId: Int,
                                                  characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean,
                                                  responseNeeded: Boolean,
                                                  offset: Int,
                                                  value: ByteArray) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            RxBleLog.v("onCharacteristicWriteRequest characteristic: " + characteristic.uuid
                    + " device: " + device.address + " responseNeeded " + responseNeeded)
            val rxBleDevice: RxBleDevice = client.getBleDevice(device.address)
            if (preparedWrite) {
                RxBleLog.v("characteristic long write")
                val longWriteOuput: Output<ByteArray> = openLongWriteCharacteristicOutput(requestId, characteristic)
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
                }
                longWriteOuput.valueRelay.onNext(value)
            } else if (getWriteCharacteristicOutput().hasObservers()) {
                prepareCharacteristicTransaction(
                        characteristic,
                        requestId,
                        offset,
                        rxBleDevice,
                        getWriteCharacteristicOutput().valueRelay,
                        value
                )
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice,
                                             requestId: Int,
                                             offset: Int,
                                             descriptor: BluetoothGattDescriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            RxBleLog.v("onDescriptorReadRequest: " + descriptor.uuid)
            val rxBleDevice: RxBleDevice = client.getBleDevice(device.address)
            if (descriptor.uuid.compareTo(RxBleClient.CLIENT_CONFIG) == 0) {
                gattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        serverState.getNotificationValue(descriptor.characteristic.uuid)
                )
            }
            if (getReadDescriptorOutput().hasObservers()) {
                prepareDescriptorTransaction(
                        descriptor,
                        requestId,
                        offset,
                        rxBleDevice,
                        getReadDescriptorOutput().valueRelay, ByteArray(0))
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice,
                                              requestId: Int,
                                              descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean,
                                              responseNeeded: Boolean,
                                              offset: Int,
                                              value: ByteArray) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            RxBleLog.v("onDescriptorWriteRequest: " + descriptor.uuid)
            val rxBleDevice: RxBleDevice = client.getBleDevice(device.address)
            if (preparedWrite) {
                RxBleLog.v("onDescriptorWriteRequest: invoking preparedWrite")
                val longWriteOutput: Output<ByteArray> = openLongWriteDescriptorOutput(requestId, descriptor)
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
                }
                longWriteOutput.valueRelay.onNext(value) //TODO: offset
            } else {
                if (descriptor.uuid.compareTo(RxBleClient.CLIENT_CONFIG) == 0) {
                    serverState.setNotifications(descriptor.characteristic.uuid, value)
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
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
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            super.onExecuteWrite(device, requestId, execute)
            RxBleLog.v("onExecuteWrite $requestId $execute")
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
            if (execute) {
                closeLongWriteCharacteristicOutput(requestId)
                resetCharacteristicMap()
                resetDescriptorMap()
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            if (getNotificationPublishRelay().hasObservers()) {
                RxBleLog.v("onNotificationSent: " + device.address + " " + status)
                getNotificationPublishRelay().valueRelay.onNext(
                        status
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            super.onMtuChanged(device, mtu)
            if (getChangedMtuOutput().hasObservers()) {
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


    private fun initializeServer(config: ServerConfig) {
        for (phy in config.getPhySet()) {
            when (phy) {
                ServerConfig.BluetoothPhy.PHY_LE_1M, ServerConfig.BluetoothPhy.PHY_LE_2M, ServerConfig.BluetoothPhy.PHY_LE_CODED -> {}
                else ->                         // here to please linter
                    Log.e("debug", "we should never reach here")
            }
        }
        for ((_, value) in config.getServices()) {
            registerService(value)
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
                        val both = Arrays.copyOf(first, first.size + second.size)
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

    override fun openLongWriteDescriptorOutput(requestid: Int, descriptor: BluetoothGattDescriptor): LongWriteClosableOutput<ByteArray> {
        var output = descriptorMultiIndex.get(requestid)
        if (output == null) {
            output = LongWriteClosableOutput()
            output.valueRelay
                    .reduce { first, second ->
                        val both = Arrays.copyOf(first, first.size + second.size)
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
                    val output  = characteristicMultiIndex.get(integer)
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

    override fun setupIndication(ch: UUID, indications: Flowable<ByteArray>, device: RxBleDevice): Completable {
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
        return Single.fromCallable(Callable {
            if (isIndication) {
                if (serverState.getIndications(characteristic.uuid)) {
                    RxBleLog.v("immediate start indication")
                    return@Callable Completable.complete()
                }
            } else {
                if (serverState.getNotifications(characteristic.uuid)) {
                    RxBleLog.v("immediate start notification")
                    return@Callable Completable.complete()
                }
            }
            withDisconnectionHandling(getWriteDescriptorOutput())
                    .filter { transaction ->
                        (transaction.first.uuid.compareTo(clientconfig.uuid) == 0
                                && transaction.first.characteristic.uuid
                                .compareTo(clientconfig.characteristic.uuid) == 0)
                    }
                    .takeWhile { trans -> Arrays.equals(trans.second.value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) }
                    .ignoreElements()
        }).flatMapCompletable { completable -> completable }
    }

    override fun setupNotifications(ch: UUID, notifications: Flowable<ByteArray>, device: RxBleDevice): Completable {
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
        return Single.fromCallable(Callable {
            RxBleLog.v("setupNotifictions: " + characteristic.uuid)
            val clientconfig = characteristic.getDescriptor(RxBleClient.CLIENT_CONFIG)
                    ?: return@Callable Completable.error(BleGattServerException(
                            BleGattServerOperationType.NOTIFICATION_SENT,
                            "client config was null when setting up notifications"
                    ))
            notifications
                    .takeWhile {
                        (bluetoothManager.getConnectionState(device.bluetoothDevice, BluetoothProfile.GATT_SERVER)
                                == BluetoothProfile.STATE_CONNECTED)
                    }
                    .subscribeOn(connectionScheduler)
                    .delay<ByteArray> {
                        setupNotificationsDelay(clientconfig, characteristic, isIndication)
                                .toFlowable()
                    }
                    .concatMap { bytes ->
                        RxBleLog.v("processing bytes length: " + bytes.size)
                        val operation = operationsProvider.provideNotifyOperation(
                                characteristic,
                                bytes,
                                isIndication,
                                device
                        )
                        RxBleLog.v("queueing notification/indication")
                        operationQueue.queue(operation).toFlowable(BackpressureStrategy.BUFFER)
                    }
                    .flatMap { integer ->
                        RxBleLog.v("notification result: $integer")
                        if (integer != BluetoothGatt.GATT_SUCCESS) {
                            Flowable.error(BleGattServerException(
                                    BleGattServerOperationType.NOTIFICATION_SENT,
                                    "notification operation did not return GATT_SUCCESS"
                            ))
                        } else {
                            Flowable.just(integer)
                        }
                    }
                    .ignoreElements()
                    .doOnComplete { RxBleLog.v("notifications completed!") }
        }).flatMapCompletable { completable -> completable }
    }

    private fun <T> withDisconnectionHandling(output: Output<T>): Observable<T> {
        return Observable.merge(
                output.valueRelay,
                output.errorRelay.flatMap(errorMapper) as Observable<T>
        )
    }

    fun getOnMtuChanged(): Observable<Int> {
        return withDisconnectionHandling(getChangedMtuOutput())
                .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    fun observeDisconnect(): Observable<RxBleDevice> {
        return connectionStatePublishRelay
                .filter { pair -> pair.second == RxBleConnectionState.DISCONNECTED }
                .map { pair -> pair.first }
                .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    fun observeConnect(): Observable<RxBleDevice> {
        return connectionStatePublishRelay
                .filter { pair -> pair.second == RxBleConnectionState.CONNECTED }
                .map { pair -> pair.first }
                .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    fun getOnCharacteristicReadRequest(characteristic: UUID): Observable<ServerResponseTransaction> {
        return withDisconnectionHandling(getReadCharacteristicOutput())
                .filter { uuidGattServerTransaction -> uuidGattServerTransaction.first.compareTo(characteristic) == 0 }
                .map { uuidGattServerTransaction -> uuidGattServerTransaction.second }
                .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }


    fun getOnCharacteristicWriteRequest(characteristic: UUID): Observable<ServerResponseTransaction> {
        return withDisconnectionHandling(getWriteCharacteristicOutput())
                .filter { uuidGattServerTransaction -> uuidGattServerTransaction.first.compareTo(characteristic) == 0 }
                .map { uuidGattServerTransaction -> uuidGattServerTransaction.second }
                .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    fun getOnDescriptorReadRequest(
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

    fun getOnDescriptorWriteRequest(
            characteristicuuid: UUID,
            descriptoruuid: UUID
    ): Observable<ServerResponseTransaction> {
        return withDisconnectionHandling(getWriteDescriptorOutput())
                .filter { transaction ->
                    (transaction.first.uuid.compareTo(descriptoruuid) == 0
                            && transaction.first.characteristic.uuid
                            .compareTo(characteristicuuid) == 0)
                }
                .map { transaction -> transaction.second }
                .delay(0, TimeUnit.SECONDS, callbackScheduler)
    }

    override fun getOnNotification(): Observable<Int> {
        return notificationPublishRelay.valueRelay
    }

    fun disconnect(device: RxBleDevice): Completable {
        return operationQueue.queue<Void>(operationsProvider.provideDisconnectOperation(device)).ignoreElements()
    }

    override fun prepareDescriptorTransaction(
            descriptor: BluetoothGattDescriptor,
            requestID: Int,
            offset: Int, device: RxBleDevice,
            valueRelay: PublishSubject<GattServerTransaction<BluetoothGattDescriptor>>,
            value: ByteArray?
    ) {
        val transaction= serverTransactionFactory.prepareCharacteristicTransaction(
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
            offset: Int, device: RxBleDevice,
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
        valueRelay.onNext(GattServerTransaction(descriptor.uuid, transaction))
    }

    override fun blindAck(requestID: Int, status: Int, value: ByteArray, device: RxBleDevice): Observable<Boolean> {
        return operationQueue.queue<Boolean>(operationsProvider.provideReplyOperation(
                device,
                requestID,
                status,
                0,
                value
        ))
    }

    override fun dispose() {
        gattServer.close()
        connectionScheduler.shutdown()
        callbackScheduler.shutdown()
        compositeDisposable.dispose()
    }

    override fun isDisposed(): Boolean {
        return compositeDisposable.isDisposed
    }

    override val server: BluetoothGattServer
        get() = gattServer

    init {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        initializeServer(config)
    }

}