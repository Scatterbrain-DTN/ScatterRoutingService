package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.app.AlarmManager
import android.bluetooth.*
import android.bluetooth.le.AdvertisingSetCallback
import android.content.Context
import android.os.Build
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.mock.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.mock.network.bluetoothle.FakeGattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.mock.FakeRoutingServiceComponent
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.scatterproto.Optional
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle
import net.ballmerlabs.uscatterbrain.db.entities.MerkleDao
import net.ballmerlabs.uscatterbrain.db.entities.MerkleInsertCond
import net.ballmerlabs.uscatterbrain.mock.network.bluetoothle.MockCachedLeConnection
import net.ballmerlabs.uscatterbrain.mock.network.bluetoothle.MockLeState
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.proto.getHashUuid

import net.ballmerlabs.uscatterbrain.mock.network.wifidirect.MockWifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.util.MockFirebaseWrapper
import net.ballmerlabs.uscatterbrain.mock.util.MockRouterPreferences
import net.ballmerlabs.uscatterbrain.util.getBogusRxBleDevice
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.mock.util.mockLoggerGenerator
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.util.toBytes
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import scatterbrain.Bootstrap
import scatterbrain.Scatterbrain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AdvertiserTest {
    init {
        System.setProperty("jna.library.path", "/opt/homebrew/lib")
        logger = mockLoggerGenerator
    }


    private val scheduler = RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test-single"))
    private val ioScheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test-io"))

    private lateinit var disposable: CompositeDisposable

    @Mock
    private lateinit var manager: BluetoothManager

    @Mock
    private lateinit var alarmmanager: AlarmManager

    private lateinit var preferences: MockRouterPreferences

    private lateinit var advertiser: AdvertiserImpl
    lateinit var leState: MockLeState

    @Mock
    private lateinit var context: Context

    private lateinit var fakeRoutingServiceComponent: FakeRoutingServiceComponent
    private lateinit var fakeGattServerConnection: FakeGattServerConnectionSubcomponent

    init {
        logger = mockLoggerGenerator
    }

    private fun setupModule(): FakeRoutingServiceComponent {
        preferences = MockRouterPreferences()
        val fake =  DaggerFakeRoutingServiceComponent.builder()
            .applicationContext(context)
            .wifiP2pManager(mock { })
            .rxBleClient(mock {  })
            .packetOutputStream(ByteArrayOutputStream())
            .packetInputStream(ByteArrayInputStream(byteArrayOf()))
            .wifiDirectBroadcastReceiver(MockWifiDirectBroadcastReceiver(mock { }))
            .mockPreferences(preferences)
            .bluetoothManager(manager)
            .wifiManager(mock {  })
            .build()!!
        fakeGattServerConnection =  fake.gattConnectionBuilder()
            .gattServer(mock {  })
            .timeoutConfiguration(mock {  })
            .build() as FakeGattServerConnectionSubcomponent
        fakeRoutingServiceComponent = fake
        return fake
    }

    private fun reInit() {
        disposable = CompositeDisposable()
        setupModule()
        leState = MockLeState(
            serverConnection = fakeGattServerConnection
        )
        val mockDao = object : MerkleDao() {

            override fun nukeAllBundles(): Completable {
                TODO("Not yet implemented")
            }
            override fun getMessagesForBundle(id: Long): List<HashlessScatterMessage> {
                TODO("Not yet implemented")
            }

            override fun getMerkleBundleUnderLimitRecursive(parent: ByteArray): Single<MerkleBundle> {
                TODO("Not yet implemented")
            }

            override fun updateParentFromHash(
                childHash: ByteArray,
                pos: Int,
                child: Long,
                self: Long,
            ): Completable {
                TODO("Not yet implemented")
            }

            override fun setBundleHash(hash: ByteArray, self: Long): Completable {
                TODO("Not yet implemented")
            }

            override fun getMessagesForBundleRecursive(id: Long): Single<List<HashlessScatterMessage>> {
                TODO("Not yet implemented")
            }

            override fun getSiblingsExcludingHash(
                id: Long,
                hash: ByteArray,
            ): Single<List<MerkleBundle>> {
                TODO("Not yet implemented")
            }

            override fun getTopRandomExcludingHash(
                id: Long,
                count: Int,
                hashes: List<ByteArray>,
                flag: List<Int>?,
                fileSize: Long?
            ): Single<List<DbMessage>> {
                TODO("Not yet implemented")
            }

            override fun getNotHashes(hashes: List<ByteArray>): Single<List<Long>> {
                TODO("Not yet implemented")
            }

            override fun testBundlesExcludingHash(hashes: List<ByteArray>): List<MerkleBundle> {
                TODO("Not yet implemented")
            }

            override fun getInsertionPoint(
                hash: ByteArray,
                root: Long,
                pos: Long,
            ): MerkleInsertCond {
                TODO("Not yet implemented")
            }


            override fun getInsertionPoints(
                hash: ByteArray,
                root: Long,
                pos: Long,
            ): Single<List<MerkleInsertCond>> {
                TODO("Not yet implemented")
            }

            override fun getRoots(): Single<List<MerkleBundle>> {
                TODO("Not yet implemented")
            }

            override fun getRootsRandom(): Single<List<MerkleBundle>> {
                return Single.just(listOf(MerkleBundle(
                    id = 1,
                    hash = LibsodiumInterface.merkleHash(byteArrayOf())
                )))
            }

            override fun insertBundleEntity(bundles: List<MerkleBundle>): Single<List<Long>> {
                TODO("Not yet implemented")
            }

            override fun insertBundleEntity(bundle: MerkleBundle): Single<Long> {
                TODO("Not yet implemented")
            }

            override fun insertBundleEntitySync(bundles: List<MerkleBundle>): List<Long> {
                TODO("Not yet implemented")
            }

            override fun updateParentChildOne(parent: Long, child: Long){
                TODO("Not yet implemented")
            }

            override fun updateParentChildTwo(parent: Long, child: Long) {
                TODO("Not yet implemented")
            }

            override fun updateBundleForMessage(bundle: Long, messageID: Long) {
                TODO("Not yet implemented")
            }

            override fun getMessageCount(): Single<Long> {
                TODO("Not yet implemented")
            }

            override fun getBundle(id: Long): MerkleBundle {
                TODO("Not yet implemented")
            }

            override fun getDirtyNodes(root: Long): Single<List<Long>> {
                TODO("Not yet implemented")
            }

            override fun getNextHub(root: Long?): MerkleBundle? {
                TODO("Not yet implemented")
            }

            override fun getByHash(hash: ByteArray): Single<Int> {
                TODO("Not yet implemented")
            }

            override fun getAncestors(id: Long, limit: Int): Single<List<Long>> {
                TODO("Not yet implemented")
            }

            override fun getChildOne(id: Long): Long? {
                TODO("Not yet implemented")
            }

            override fun getChildTwo(id: Long): Long? {
                TODO("Not yet implemented")
            }

            override fun getDirty(): Single<List<MerkleBundle>> {
                TODO("Not yet implemented")
            }

            override fun updateBundleHash(hash: ByteArray, id: Long) {
                TODO("Not yet implemented")
            }

            override fun getAllBundles(): List<MerkleBundle> {
                TODO("Not yet implemented")
            }

            override fun testBitmask(hash: ByteArray, pos: Long): Boolean {
                TODO("Not yet implemented")
            }

            override fun testBitmaskHex(hash: ByteArray, pos: Int): String {
                TODO("Not yet implemented")
            }

            override fun getBundlesForBundle(id: Long): List<MerkleBundle> {
                TODO("Not yet implemented")
            }

            override fun getAllHashes(): List<ByteArray> {
                TODO("Not yet implemented")
            }

        }
        advertiser = AdvertiserImpl(
            context = context,
            firebase = MockFirebaseWrapper(),
            leState = { leState },
            manager = manager,
            wakeLockProvider = mock {  },
            advertiseScheduler = scheduler,
            timeoutScheduler = scheduler,
            wifiDirectBroadcastReceiver = MockWifiDirectBroadcastReceiver(mock()),
            database = mock {
                on { merkleDao() } doReturn mockDao
            }
        )
    }

    @Before
    fun init() {
        MockitoAnnotations.openMocks(this)
        reInit()
    }

    @After
    fun cleanup() {
        Mockito.validateMockitoUsage()
        disposable.dispose()
    }

    @Test
    fun shrinkUkes() {
        val a: BluetoothAdapter = mock {
            on { leMaximumAdvertisingDataLength } doReturn 20
        }
        manager = mock {
            on { adapter } doReturn a
        }
        reInit()

        val ukes = mutableMapOf<UUID, UpgradePacket>()
        var s = 0.toLong()
        for (x in 0..40) {
            val packet = UpgradePacket.newBuilder(Bootstrap.Role.UKE)
                .setProvides(Provides.BLE)
                .setSessionID(1)
                .setFrom(UUID.randomUUID())
                .build()!!
            val uuid = UUID.randomUUID()
            s += uuid.toBytes().size
            s += packet.packet.toByteArray().size
            ukes[uuid] = packet
        }
        assert(s > 20)

        val packet = advertiser.shrinkUkes(ukes)
        assert(packet.packet.toByteArray().size < 20)
    }

    @Test
    fun getLuid() {
        val luid = advertiser.getRawLuid()
        val hash = advertiser.getHashLuid()
        assertNotEquals(luid, hash)
        assertEquals(getHashUuid(luid), hash)
    }

    @Test
    fun randomizeAndRemove() {
        val luid = UUID.randomUUID()

        val fakeConnection =  fakeGattServerConnection.transaction()
            .connection(
                MockCachedLeConnection(
                ioScheduler = ioScheduler,
                bleDevice = getBogusRxBleDevice("ff:ff:ff:ff:ff:ff"),
                state = leState,
                leAdvertiser = advertiser,
                luid = luid,
                channelNotif = InputStreamObserver(8000)
            )
            )
            .luid(luid)
            .device(mock {  })
            .build()!!

        leState.connectionCache[luid] = fakeConnection
        val hash = advertiser.getHashLuid()
        advertiser.randomizeLuidAndRemove()

        advertiser.awaitNotBusy()
            .mergeWith(Completable.timer(10, TimeUnit.SECONDS).andThen(
                Completable.fromAction {
                advertiser.isAdvertising.onNext(Pair(Optional.of(mock {  }), AdvertisingSetCallback.ADVERTISE_SUCCESS))
                advertiser.advertisingDataUpdated.onNext(AdvertisingSetCallback.ADVERTISE_SUCCESS)
            }))
            .blockingAwait()
        assertNotEquals(hash, advertiser.getHashLuid())

    }
}