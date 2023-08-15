package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import androidx.room.util.convertUUIDToByte
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.UkeAnnouncePacket
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser.Companion.CLEAR_DATA;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser.Companion.LUID_DATA
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser.Companion.UKES_DATA
import net.ballmerlabs.uscatterbrain.network.getHashUuid
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AdvertiserImpl @Inject constructor(
    val context: Context,
    private val manager: BluetoothManager,
    private val firebase: FirebaseWrapper,
    private val state: Provider<LeState>,
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION) private val scheduler: Scheduler,
) : Advertiser {
    override val ukes: ConcurrentHashMap<UUID, UpgradePacket> = ConcurrentHashMap<UUID, UpgradePacket>()

    private val LOG by scatterLog()
    private val advertisingLock = AtomicReference(false)
    private val isAdvertising = BehaviorSubject.create<Pair<Optional<AdvertisingSet>, Int>>()
    private val advertisingDataUpdated = PublishSubject.create<Int>()
    // luid is a temporary unique identifier used for a single transaction.
    private val myLuid: AtomicReference<UUID> = AtomicReference(UUID.randomUUID())
    private val lastLuidRandomize = AtomicReference(Date())
    private val clear = AtomicBoolean()


    override fun getHashLuid(): UUID {
        return getHashUuid(myLuid.get())!!
    }

    override fun getRawLuid(): UUID {
        return myLuid.get()
    }

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
            LOG.w("onScanResponseDataSet $status $ADVERTISE_SUCCESS")
            advertisingDataUpdated.onNext(status)
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            advertisingDataUpdated.onNext(status)
        }

        override fun onPeriodicAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            super.onPeriodicAdvertisingDataSet(advertisingSet, status)
            LOG.w("set periodic data")
        }
    }

    override fun setAdvertisingLuid(): Completable {
        val currentLuid = getHashLuid()
        return setAdvertisingLuid(currentLuid, ukes)
    }

    override fun clear(boolean: Boolean) {
        clear.set(boolean)
    }

    override fun setAdvertisingLuid(luid: UUID, ukes: Map<UUID, UpgradePacket>): Completable {
        val cmp =  Completable.defer {
            isAdvertising
                .firstOrError()
                .flatMapCompletable { v ->
                    if (v.first.isPresent) {
                        awaitAdvertiseDataUpdate()
                            .mergeWith(Completable.fromAction {
                                try {
                                    val shrink = shrinkUkes(ukes)
                                    val u = shrink.packet.toByteArray()
                                    val builder = AdvertiseData.Builder()
                                        .setIncludeDeviceName(false)
                                        .setIncludeTxPowerLevel(false)
                                        .addServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))
                                        .addServiceData(ParcelUuid(LUID_DATA), convertUUIDToByte(luid))
                                    if(clear.get())
                                        builder.addServiceData(ParcelUuid(CLEAR_DATA), byteArrayOf())

                                    if(shrink.packet.ukesCount > 0)
                                        builder.addServiceData(ParcelUuid(UKES_DATA), u)
                                    v.first.item!!.setAdvertisingData(builder.build())
                                } catch (exc: SecurityException) {
                                    throw exc
                                }
                            })
                    } else {
                        startAdvertise(luid = luid)
                    }
                }
        }.doOnError { err -> LOG.w("failed to set advertising luid: $err, retry") }
        return retryDelay(cmp, 10, 5)
            .doOnError { err -> LOG.e("FATAL: failed to set advertising data, out of retries: $err") }
    }


    private fun estimateSize(size: Int): Int {
        return size + 16*2
    }

    private fun shrinkUkes(ukes: Map<UUID, UpgradePacket>): UkeAnnouncePacket {
        val packet = UkeAnnouncePacket.newBuilder()
        packet.setforceUke(ukes)
        val b = packet.build()
        val size = estimateSize(b.packet.toByteArray().size)
        val target = manager.adapter.leMaximumAdvertisingDataLength - ((2*3) + 16 + 1)
        if(size < target) {
            return b
        } else {
            if (ukes.size == 1) {
                return UkeAnnouncePacket.empty()
            }
            val n = ukes.toMutableMap()
            n.remove(n.keys.first())
            return shrinkUkes(n)
        }
    }

    private fun awaitAdvertiseDataUpdate(): Completable {
        return advertisingDataUpdated
            .firstOrError()
            .flatMapCompletable { status ->
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS)
                    Completable.complete()
                else
                    Completable.error(IllegalStateException("failed to set advertising data: $status"))
            }.subscribeOn(scheduler)

    }

    override fun removeLuid(): Completable {
        return Completable.defer {
            isAdvertising
                .firstOrError()
                .flatMapCompletable { v ->
                    if (v.first.isPresent) {
                        awaitAdvertiseDataUpdate()
                            .mergeWith(Completable.fromAction {
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
                            })
                    } else {
                        Completable.error(IllegalStateException("failed to set advertising data removeLuid"))
                    }
                }
        }
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
        }.subscribeOn(scheduler)
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
            }.subscribeOn(scheduler)

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
                            serviceDataBuilder.addServiceData(ParcelUuid(LUID_DATA), convertUUIDToByte(luid))
                                .build()
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
                    }
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
            }.subscribeOn(scheduler)

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