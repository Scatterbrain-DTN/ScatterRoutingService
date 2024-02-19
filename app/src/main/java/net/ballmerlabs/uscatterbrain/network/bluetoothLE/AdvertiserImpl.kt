package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
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
import net.ballmerlabs.uscatterbrain.util.toBytes
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
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION) private val scheduler: Scheduler,
    private val alarmManager: AlarmManager,
    private val leState: Provider<LeState>
) : Advertiser {
    override val ukes: ConcurrentHashMap<UUID, UpgradePacket> =
        ConcurrentHashMap<UUID, UpgradePacket>()

    private val LOG by scatterLog()
    private val advertisingLock = AtomicReference(false)
    private val isAdvertising = BehaviorSubject.create<Pair<Optional<AdvertisingSet>, Int>>()
    private val isLegacyAdvertising =
        BehaviorSubject.create<Pair<Optional<AdvertiseSettings>, Int>>()
    private val advertisingDataUpdated = PublishSubject.create<Int>()

    // luid is a temporary unique identifier used for a single transaction.
    private val myLuid: AtomicReference<UUID> = AtomicReference(UUID.randomUUID())
    private val lastLuidRandomize = AtomicReference(Date())
    private val randomizeLuidDisp = AtomicReference<Disposable?>(null)
    private val clear = AtomicBoolean()
    private val busy = BehaviorSubject.create<Boolean>()
    private val legacyLuid = AtomicReference<UUID?>(null)

    override fun awaitNotBusy(): Completable {
        return busy.takeUntil { v -> !v }.ignoreElements()
    }

    override fun getHashLuid(): UUID {
        return getHashUuid(myLuid.get())!!
    }

    override fun getRawLuid(): UUID {
        return myLuid.get()
    }

    override fun randomizeLuidAndRemove() {
        busy.onNext(true)
        val disp = Completable.fromAction {
            leState.get().connectionCache.forEach { (k, v) ->
                leState.get().updateGone(k)
                v.connection().dispose()
            }
            myLuid.set(UUID.randomUUID())
        }.andThen(removeLuid())
            .doFinally { busy.onNext(false) }
            .subscribe(
                { LOG.w("successfully removed and randomized luid") },
                { err -> LOG.e("failed to remove and randomize luid $err") }
            )
        randomizeLuidDisp.getAndSet(disp)?.dispose()
    }

    private val legacyCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            legacyLuid.set(null)
            isLegacyAdvertising.onNext(Pair(Optional.empty(), errorCode))

        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            if (settingsInEffect != null) {
                isLegacyAdvertising.onNext(Pair(Optional.of(settingsInEffect), 0))
            } else {
                isLegacyAdvertising.onNext(Pair(Optional.empty(), 0))
            }
        }
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

    override fun getAlarmIntent(): PendingIntent {
        return Intent(context, LuidRandomizeReceiver::class.java).let {
            PendingIntent.getBroadcast(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
        }
    }

    override fun setAdvertisingLuid(): Completable {
        val currentLuid = getHashLuid()
        return setAdvertisingLuid(currentLuid, ukes)
    }

    override fun clear(boolean: Boolean) {
        clear.set(boolean)
    }

    override fun setRandomizeTimer(minutes: Int) {
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60 * 1000 * minutes,
            getAlarmIntent()
        )
    }

    override fun setAdvertisingLuid(luid: UUID, ukes: Map<UUID, UpgradePacket>): Completable {
        val cmp = stopLegacy(luid)
            .andThen(startLegacy(luid))
            .andThen(Completable.defer {
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
                                            .addServiceData(ParcelUuid(LUID_DATA), luid.toBytes())
                                        if (clear.get())
                                            builder.addServiceData(
                                                ParcelUuid(CLEAR_DATA),
                                                byteArrayOf()
                                            )

                                        // TODO: why the absolute fuck did I do this?
                                        if (shrink.packet.ukesCount > 0 && false)
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
            }.doOnError { err -> LOG.w("failed to set advertising luid: $err, retry") })
        return retryDelay(cmp, 10, 5)
            .doOnError { err -> LOG.e("FATAL: failed to set advertising data, out of retries: $err") }
    }


    private fun estimateSize(size: Int): Int {
        return size + 16 * 2
    }

    fun shrinkUkes(ukes: Map<UUID, UpgradePacket>): UkeAnnouncePacket {
        val packet = UkeAnnouncePacket.newBuilder()
        packet.setforceUke(ukes)
        val b = packet.build()
        val size = estimateSize(b.packet.toByteArray().size)
        val target = manager.adapter.leMaximumAdvertisingDataLength - ((2 * 3) + 16 + 1)
        if (size < target) {
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
        return stopLegacy()
            .andThen(startLegacy())
            .onErrorComplete()
            .andThen(Completable.defer {
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
                                                .addServiceUuid(
                                                    ParcelUuid(
                                                        BluetoothLERadioModuleImpl.SERVICE_UUID
                                                    )
                                                )
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
                .doOnComplete { LOG.v("successfully removed luid") })
    }

    /**
     * stop offloaded advertising
     */
    override fun stopAdvertise(): Completable {
        LOG.v("stopping LE advertise")
        return stopLegacy().andThen(Completable.defer {
            try {
                manager.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advertiseSetCallback)
            } catch (exc: SecurityException) {
                throw exc
            }
            mapAdvertiseComplete(false)
        }.subscribeOn(scheduler))
    }


    fun startLegacy(uuid: UUID? = null): Completable {
        return Completable.defer {
            val current = legacyLuid.get()
            if (current != uuid || uuid == null) {
                val advertiseSettings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build()
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))
                    .build()

                val response = if (uuid != null)
                    AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .addServiceData(ParcelUuid(LUID_DATA), uuid.toBytes())
                        .build()
                else
                    null
                try {
                    manager.adapter.bluetoothLeAdvertiser.startAdvertising(
                        advertiseSettings,
                        data,
                        response,
                        legacyCallback
                    )
                } catch (exc: SecurityException) {
                    LOG.e("securityException in startLegacy $exc")
                    firebase.recordException(exc)
                    throw exc
                }
                isLegacyAdvertising.takeUntil { v -> v.first.isPresent && v.second == 0 }
                    .ignoreElements()
            } else {
                Completable.complete()
            }
        }.doOnComplete { legacyLuid.set(uuid) }
    }


    fun stopLegacy(luid: UUID? = null): Completable {
        return Completable.fromAction {
            val current = legacyLuid.get()
            if (current == luid || luid == null) {
                try {
                    manager.adapter.bluetoothLeAdvertiser.stopAdvertising(legacyCallback)
                } catch (exc: SecurityException) {
                    LOG.e("securityException in stopLegacy $exc")
                    firebase.recordException(exc)
                    throw exc
                }
                isLegacyAdvertising.onNext(Pair(Optional.empty(), 0))
            }
        }
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
        val advertise = isLegacyAdvertising.firstOrError().flatMapCompletable { v ->
            if (v.first.isPresent)
                Completable.complete()
            else
                startLegacy(luid)
        }
            .onErrorComplete()
            .andThen(isAdvertising)
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
                            serviceDataBuilder.addServiceData(ParcelUuid(LUID_DATA), luid.toBytes())
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
        isLegacyAdvertising.onNext(Pair(Optional.empty(), 0))
    }
}