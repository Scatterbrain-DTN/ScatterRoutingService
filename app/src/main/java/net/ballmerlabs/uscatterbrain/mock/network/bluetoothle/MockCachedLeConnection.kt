package net.ballmerlabs.uscatterbrain.mock.network.bluetoothle

import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLEConnectionImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLeConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import java.util.UUID

class MockCachedLeConnection(
    ioScheduler: Scheduler,
    bleDevice: RxBleDevice,
    state: LeState,
    leAdvertiser: Advertiser,
    luid: UUID,
    override val channelNotif: InputStreamObserver = InputStreamObserver(8000),
    override val connection: BehaviorSubject<RxBleConnection> = BehaviorSubject.create(),
) : CachedLeConnection, CachedLEConnectionImpl(
    ioScheduler,
    ioScheduler,
    bleDevice,
    leAdvertiser,
    state,
    luid) {

    override fun sendForget(luid: UUID): Completable {
        return Completable.complete()
    }
}