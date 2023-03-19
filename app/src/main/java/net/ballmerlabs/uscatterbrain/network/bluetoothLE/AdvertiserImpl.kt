package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.getHashUuid
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AdvertiserImpl @Inject constructor(
    val context: Context,
    private val manager: BluetoothManager,
    private val firebase: FirebaseWrapper,
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION)
    private val scheduler: Scheduler
) : Advertiser {

    private val LOG by scatterLog()
    private val advertisingLock = AtomicReference(false)
    private val isAdvertising = BehaviorSubject.create<Pair<Optional<AdvertisingSet>, Int>>()
    private val advertisingDataUpdated = PublishSubject.create<Int>()

    // luid is a temporary unique identifier used for a single transaction.
    override val myLuid: AtomicReference<UUID> = AtomicReference(UUID.randomUUID())
    private val lastLuidRandomize = AtomicReference(Date())


    // map advertising state to rxjava2
    private val advertiseSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?,
            txPower: Int,
            status: Int
        ) {
            LOG.v("successfully started advertise $status")
            if (advertisingSet != null) {
                isAdvertising.onNext(Pair(Optional.of(advertisingSet), status))
            } else {
                isAdvertising.onNext(Pair(Optional.empty(), status))
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            LOG.e("advertise stopped")
            isAdvertising.onNext(Pair(Optional.empty(), ADVERTISE_SUCCESS))
        }

        override fun onScanResponseDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            advertisingDataUpdated.onNext(status)
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            advertisingDataUpdated.onNext(status)
        }
    }

    override fun setAdvertisingLuid(): Completable {
        val currentLuid = getHashUuid(myLuid.get()) ?: UUID.randomUUID()
        return setAdvertisingLuid(currentLuid)
            .subscribeOn(scheduler)
    }

    override fun setAdvertisingLuid(luid: UUID): Completable {
        return Completable.defer {
            isAdvertising
                .take(1)
                .flatMapCompletable { v ->
                    if (v.first.isPresent) {
                        awaitAdvertiseDataUpdate()
                            .doOnSubscribe {
                                try {
                                    v.first.item?.setAdvertisingData(
                                        AdvertiseData.Builder()
                                            .setIncludeDeviceName(false)
                                            .setIncludeTxPowerLevel(false)
                                            .addServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))
                                            .addServiceData(ParcelUuid(luid), byteArrayOf(5))
                                            .build()
                                    )
                                } catch (exc: SecurityException) {
                                    throw exc
                                }
                            }
                    } else {
                        startAdvertise(luid = luid)
                    }
                }
        }.subscribeOn(scheduler)
    }

    private fun awaitAdvertiseDataUpdate(): Completable {
        return advertisingDataUpdated
            .firstOrError()
            .flatMapCompletable { status ->
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS)
                    Completable.complete()
                else
                    Completable.error(IllegalStateException("failed to set advertising data"))
            }
            .subscribeOn(scheduler)
    }

    override fun removeLuid(): Completable {
        return Completable.defer {
            isAdvertising
                .firstOrError()
                .flatMapCompletable { v ->
                    if (v.first.isPresent) {
                        awaitAdvertiseDataUpdate()
                            .doOnSubscribe {
                                try {
                                    v.first.item?.setAdvertisingData(
                                        AdvertiseData.Builder()
                                            .setIncludeDeviceName(false)
                                            .setIncludeTxPowerLevel(false)
                                            .addServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))
                                            .build()
                                    )
                                } catch (exc: SecurityException) {
                                    throw exc
                                }
                            }
                    } else {
                        Completable.error(IllegalStateException("failed to set advertising data removeLuid"))
                    }
                }
        }
            .subscribeOn(scheduler)
            .doOnComplete { LOG.v("successfully removed luid") }
    }

    /**
     * stop offloaded advertising
     */
    override fun stopAdvertise(): Completable {
        LOG.v("stopping LE advertise")
        return Completable.fromAction {
            try {
                manager.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advertiseSetCallback)
            } catch (exc: SecurityException) {
                throw exc
            }
            mapAdvertiseComplete(false)
        }
            .subscribeOn(scheduler)
    }


    private fun mapAdvertiseComplete(state: Boolean): Completable {
        return isAdvertising
            .takeUntil { v -> (v.first.isPresent == state) || (v.second != AdvertisingSetCallback.ADVERTISE_SUCCESS) }
            .flatMapCompletable { v ->
                if (v.second != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    Completable.error(IllegalStateException("failed to complete advertise task: ${v.second}"))
                } else {
                    Completable.complete()
                }
            }

    }

    /**
     * start offloaded advertising. This should continue even after the phone is asleep
     */
    override fun startAdvertise(luid: UUID?): Completable {
        val advertise = isAdvertising
            .firstOrError()
            .flatMapCompletable { v ->
                if (v.first.isPresent && (v.second == AdvertisingSetCallback.ADVERTISE_SUCCESS))
                    Completable.complete()
                else
                    Completable.fromAction {
                        LOG.v("Starting LE advertise")
                        val settings = AdvertisingSetParameters.Builder()
                            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                            .setLegacyMode(false)
                            .setConnectable(true)
                            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                            .build()
                        val serviceDataBuilder = AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .setIncludeTxPowerLevel(false)
                            .addServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))

                        val serviceData = if (luid != null) {
                          serviceDataBuilder.addServiceData(ParcelUuid(luid), byteArrayOf(5)).build()
                        } else {
                          serviceDataBuilder.build()
                        }

                        try {
                            manager.adapter.bluetoothLeAdvertiser.startAdvertisingSet(
                                settings,
                                serviceData,
                                null,
                                null,
                                null,
                                advertiseSetCallback
                            )
                        } catch (exc: SecurityException) {
                            throw exc
                        } catch (exc: Exception) {
                            LOG.e("failed to advertise $exc")
                        }
                        LOG.v("advertise start")
                    }.subscribeOn(scheduler)
                        .andThen(mapAdvertiseComplete(true))
            }
            .doOnError { err ->
                firebase.recordException(err)
                LOG.e("error in startAdvertise $err")
                err.printStackTrace()
            }
            .doFinally {
                LOG.v("startAdvertise completed")
                advertisingLock.set(false)
            }

        return advertise
    }

    override fun randomizeLuidIfOld(): Boolean {
        val now = Date()
        val old = lastLuidRandomize.getAndUpdate { old ->
            if (now.compareTo(old) > BluetoothLERadioModuleImpl.LUID_RANDOMIZE_DELAY) {
                now
            } else {
                old
            }
        }
        return old.compareTo(now) > BluetoothLERadioModuleImpl.LUID_RANDOMIZE_DELAY
    }

    init {
        isAdvertising.onNext(Pair(Optional.empty(), AdvertisingSetCallback.ADVERTISE_SUCCESS))
    }
}