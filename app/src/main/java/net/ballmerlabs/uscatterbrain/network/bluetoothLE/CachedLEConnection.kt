package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.Context
import android.util.Log
import com.polidea.rxandroidble2.NotificationSetupMode
import com.polidea.rxandroidble2.RxBleConnection
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.SingleSource
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Function
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.LockedCharactersitic
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CachedLEConnection(val connection: RxBleConnection, private val channels: ConcurrentHashMap<UUID, LockedCharactersitic>) : Disposable {
    private val disposable = CompositeDisposable()

    private fun selectChannel(): Single<UUID> {
        return connection.readCharacteristic(BluetoothLERadioModuleImpl.Companion.UUID_SEMAPHOR)
                .flatMap flatMap@{ bytes: ByteArray ->
                    val uuid: UUID = BluetoothLERadioModuleImpl.Companion.bytes2uuid(bytes)
                    if (!channels.containsKey(uuid)) {
                        return@flatMap Single.error<UUID>(IllegalStateException("gatt server returned invalid uuid"))
                    }
                    Single.just(uuid)
                }
    }

    private fun cachedNotification(): Observable<ByteArray> {
        val notificationDisposable = CompositeDisposable()
        return selectChannel()
                .flatMapObservable { uuid: UUID ->
                    connection.setupIndication(uuid, NotificationSetupMode.QUICK_SETUP)
                            .retry(10)
                            .doOnSubscribe { disposable: Disposable? -> Log.v(TAG, "client subscribed to notifications for $uuid") }
                            .flatMap { observable: Observable<ByteArray>? -> observable }
                            .doOnComplete { Log.e(TAG, "notifications completed for some reason") }
                            .doOnNext { b: ByteArray -> Log.v(TAG, "client received bytes " + b.size) }
                            .timeout(BluetoothLEModule.Companion.TIMEOUT.toLong(), TimeUnit.SECONDS)
                            .doFinally { notificationDisposable.dispose() }
                }
    }

    fun readAdvertise(): Single<AdvertisePacket> {
        return AdvertisePacket.Companion.parseFrom(cachedNotification())
    }

    fun readUpgrade(): Single<UpgradePacket> {
        return UpgradePacket.Companion.parseFrom(cachedNotification())
    }

    fun readBlockHeader(): Single<BlockHeaderPacket> {
        return BlockHeaderPacket.Companion.parseFrom(cachedNotification())
    }

    fun readBlockSequence(): Single<BlockSequencePacket> {
        return BlockSequencePacket.Companion.parseFrom(cachedNotification())
    }

    fun readDeclareHashes(): Single<DeclareHashesPacket> {
        return DeclareHashesPacket.Companion.parseFrom(cachedNotification())
    }

    fun readElectLeader(): Single<ElectLeaderPacket> {
        return ElectLeaderPacket.Companion.parseFrom(cachedNotification())
    }

    fun readIdentityPacket(context: Context): Single<IdentityPacket> {
        return IdentityPacket.Companion.parseFrom(cachedNotification(), context)
    }

    fun readLuid(): Single<LuidPacket> {
        return LuidPacket.Companion.parseFrom(cachedNotification())
    }

    override fun dispose() {
        disposable.dispose()
    }

    override fun isDisposed(): Boolean {
        return disposable.isDisposed
    }

    companion object {
        const val TAG = "CachedLEConnection"
    }

}