package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.network.AckPacket
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket
import net.ballmerlabs.uscatterbrain.network.ElectLeaderPacket
import net.ballmerlabs.uscatterbrain.network.IdentityPacket
import net.ballmerlabs.uscatterbrain.network.InputStreamObserver
import net.ballmerlabs.uscatterbrain.network.LuidPacket
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MockCachedLeConnection(
    ioScheduler: Scheduler,
    bleDevice: RxBleDevice,
    state: LeState,
    leAdvertiser: Advertiser,
    luid: UUID,
    override val channelNotif: InputStreamObserver = InputStreamObserver(8000),
    override val connection: BehaviorSubject<RxBleConnection> = BehaviorSubject.create(),
    override val mtu: AtomicInteger = AtomicInteger(0)
) : CachedLeConnection, CachedLEConnectionImpl(
    ioScheduler,
    bleDevice,
    leAdvertiser,
    state,
    luid) {
}