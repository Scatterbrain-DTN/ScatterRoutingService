package net.ballmerlabs.uscatterbrain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import com.geeksville.mesh.util.toHexString
import com.google.firebase.FirebaseApp
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.goterl.lazysodium.interfaces.Hash
import com.goterl.lazysodium.interfaces.Sign
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.subjects.PublishSubject
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.db.DEFAULT_BLOCKSIZE
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.mock.DaggerFakeDbRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import proto.Scatterbrain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import net.ballmerlabs.uscatterbrain.network.proto.*
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig
import net.ballmerlabs.uscatterbrain.network.wifidirect.GroupHandle
import net.ballmerlabs.uscatterbrain.network.wifidirect.PortSocket
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectInfo
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiGroupInfo
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiSessionConfig
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(AndroidJUnit4ClassRunner::class)
class ProtocolTests {
    private val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test"))
    private val writeScheduler =
        RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test2"))

    private lateinit var groupHandleOne: GroupHandle
    private lateinit var groupHandleTwo: GroupHandle

    val socket = ServerSocket(0, 32, InetAddress.getLocalHost())

    lateinit var clientSocket: Socket
    lateinit var serverSocket: Socket

    lateinit var bootstrapRequest: WifiDirectBootstrapRequest
    lateinit var bootstrapRequestTwo: WifiDirectBootstrapRequest

    lateinit var ds1: Datastore
    lateinit var ds2: Datastore
    lateinit var datastore1: ScatterbrainDatastore
    lateinit var datastore2: ScatterbrainDatastore
    lateinit var ctx: Context

    private val disp = CompositeDisposable()

    @After
    fun cleanup() {
        disp.dispose()
    }

    @Before
    fun init() {
         ctx = ApplicationProvider.getApplicationContext<Context>()


         ds1 = Room.inMemoryDatabaseBuilder(ctx, Datastore::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .fallbackToDestructiveMigration()
            .build()

         ds2 = Room.inMemoryDatabaseBuilder(ctx, Datastore::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .fallbackToDestructiveMigration()
            .build()


        val app = DaggerFakeDbRoutingServiceComponent.builder()
            .datastore(ds1)!!
            .applicationContext(ctx)!!
            .build()!!

        val app2 = DaggerFakeDbRoutingServiceComponent.builder()
            .applicationContext(ctx)!!
            .datastore(ds2)!!
            .build()!!

        datastore1 = app.datastore()
        datastore2 = app2.datastore()

        val bs = app.bootstrapRequest()
            .wifiDirectArgs(
                BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                "test",
                "secretpassphrase",
                BluetoothLEModule.Role.ROLE_UKE,
                    FakeWifiP2pConfig.GROUP_OWNER_BAND_5GHZ,
                    socket.localPort,
                    socket.inetAddress,
                    UUID.randomUUID()
            )).build()!!

        val bs2 = app2.bootstrapRequest()
            .wifiDirectArgs(
                BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                    "test",
                    "secretpassphrase",
                    BluetoothLEModule.Role.ROLE_SEME,
                    FakeWifiP2pConfig.GROUP_OWNER_BAND_5GHZ,
                    socket.localPort,
                    socket.inetAddress,
                    UUID.randomUUID()
                )).build()!!

        bootstrapRequest = bs.wifiBootstrapRequest()
        bootstrapRequestTwo = bs2.wifiBootstrapRequest()

        clientSocket = Socket(socket.inetAddress, socket.localPort)
        serverSocket = socket.accept()

        val subcompoment = app.wifiGroupSubcomponent()
            .serverSocket(serverSocket = PortSocket(socket))
            .bootstrapRequest(bootstrapRequest)
            .info(WifiSessionConfig(
                wifiDirectInfo = WifiDirectInfo(
                    true,
                    socket.inetAddress,
                    true
                ),
                wifiGroupInfo = WifiGroupInfo(
                    "test_network",
                    "testsecretpassphrase",
                    FakeWifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                )
            )).build()

        val subcompoment2 = app2.wifiGroupSubcomponent()
            .serverSocket(serverSocket = PortSocket(socket))
            .bootstrapRequest(bootstrapRequest)
            .info(WifiSessionConfig(
                wifiDirectInfo = WifiDirectInfo(
                    true,
                    socket.inetAddress,
                    true
                ),
                wifiGroupInfo = WifiGroupInfo(
                    "test_network",
                    "testsecretpassphrase",
                    FakeWifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                )
            )).build()

        groupHandleOne = subcompoment.groupHandle()

        groupHandleTwo = subcompoment2.groupHandle()

        FirebaseApp.initializeApp(ctx)
    }

    private inline fun <reified T : ScatterSerializable<V>, reified V : MessageLite> testSerialize(
        parser: ScatterSerializable.Companion.Parser<V, T>,
        input: T,
        blocksize: Int = 20,
        onComplete: (packet: T) -> Unit
    ) {
        onComplete(input)
        val buf = InputStreamFlowableSubscriber(blocksize * 1024)
        for (x in 1..blocksize) {
            val obs = input.writeToStream(x, writeScheduler).toSingle().flatMapPublisher { c -> c }
            obs.subscribe(buf)
        }

        for (x in 1..blocksize) {
            val packet = ScatterSerializable.parseWrapperFromCRC(
                parser,
                buf,
                scheduler
            ).blockingGet()
            onComplete(packet)
        }
        val stream = ByteArrayOutputStream()
        input.writeToStream(stream, scheduler).toSingle().flatMapCompletable { c -> c }.blockingAwait()
        val streamPacket = ScatterSerializable.parseWrapperFromCRC(
            parser,
            ByteArrayInputStream(stream.toByteArray()),
            scheduler
        ).blockingGet()
        onComplete(streamPacket)
    }


    @Test
    fun handleNoHashes() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(0, 2, 4))
            .setApplication("fmef")
            .build()
        datastore1.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        val root = ds1.merkleDao().getDefaultRoot().blockingGet()
        val out1 = groupHandleOne.declareHashesMerkle(clientSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF)
            .ignoreElement()


        val out2 = groupHandleTwo.declareHashesMerkle(serverSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).toObservable()
            .mergeWith(out1)
            .firstOrError()
            .blockingGet()

        assertEquals(out2.size, 0)

        val out = ds1.merkleDao().getTopRandomExcludingHash(root.id!!, 100, listOf()).blockingGet()
        assertEquals(1, out.size)


    }



    @Test
    fun merkleSyncDiffSize() {
        val big = 5
        for (x in 0..<big) {
            val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(x.toByte()))
                .setApplication("fmef")
                .build()
            datastore1.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }

        ds1.merkleDao().merkleRehash().blockingAwait()
        ds2.merkleDao().merkleRehash().blockingAwait()

        val out1 = groupHandleTwo.declareHashesMerkle(clientSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).ignoreElement()
        val out2 = groupHandleOne.declareHashesMerkle(serverSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).toObservable()
            .mergeWith(out1)
            .lastOrError()
            .blockingGet()
            .toMutableList()

        val nr = ds1.merkleDao().getDefaultRoot().blockingGet()
        println("got out2 ${out2.size}")
        val o = ds1.merkleDao().getTopRandomExcludingHash(nr.id!!, 500, out2).blockingGet()

        assertEquals(big, o.size)

        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(0))
            .setApplication("fmef")
            .build()
        datastore2.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        ds2.merkleDao().merkleRehash().blockingAwait()

        val out3 = groupHandleTwo.declareHashesMerkle(clientSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).ignoreElement()
        val out4 = groupHandleOne.declareHashesMerkle(serverSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).toObservable()
            .mergeWith(out3)
            .lastOrError()
            .blockingGet()
            .toMutableList()

        println("got out4 ${out4.size}")


        val nr2 = ds1.merkleDao().getDefaultRoot().blockingGet()
        val nr3 = ds2.merkleDao().getDefaultRoot().blockingGet()


        val o2 = ds1.merkleDao().getTopRandomExcludingHash(nr2.id!!, 500, out4).blockingGet()

        for (v in o2) {
            println("message: ${v.message.fileGlobalHash.toHexString()}")
        }

        val set1 = ds1.merkleDao().getAllHashes().map { v -> v.toHexString() }.toSet()
        val set2 = ds2.merkleDao().getAllHashes().map { v -> v.toHexString() }.toSet()

        val allhubs1 = ds1.merkleDao().getAllHubs(nr2).map { v -> v.toHexString() }.toSet()
        val allhubs2 = ds2.merkleDao().getAllHubs(nr3).map { v -> v.toHexString() }.toSet()

        val intersect = set1.intersect(set2)

        datastore2.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        println("hubs intersect ${allhubs1.size} ${allhubs2.size} ${allhubs1.intersect(allhubs2).toSortedSet()}")
        println("all intersect ${set1.size} ${set2.size} ${set1.intersect(set2).toSortedSet()}")


        println("comparing ${big-1} ${o2.size} $out4")
           assertEquals(big-1, o2.size)
    }

    @Test
    fun merkleSync() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1))
            .setApplication("fmef")
            .build()
        datastore1.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        val apiMessage2 = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(2))
            .setApplication("fmef")
            .build()
        datastore2.insertAndHashFileFromApi(apiMessage2, DEFAULT_BLOCKSIZE, "").blockingAwait()

        ds1.merkleDao().merkleRehash().blockingAwait()
        ds2.merkleDao().merkleRehash().blockingAwait()

        val out1 = groupHandleTwo.declareHashesMerkle(clientSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF)
            .doOnSuccess { out1 -> println("got out1: ${out1.map { v -> v.toByteString() }}") }
            .ignoreElement()
        val out2 = groupHandleOne.declareHashesMerkle(serverSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF)
            .toFlowable()
            .mergeWith(out1)
            .lastOrError()
            .blockingGet()
            .toMutableList()

        val nr = ds1.merkleDao().getDefaultRoot().blockingGet()
        val root2 = ds2.merkleDao().getDefaultRoot().blockingGet()
        assertNotEquals(root2.hash, nr.hash)

        println("got out2 ${out2.map { v -> v.toByteString() }}")
        val o = ds1.merkleDao().getTopRandomExcludingHash(nr.id!!, 500, out2).blockingGet()

        assertEquals(1, o.size)

        datastore2.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        ds2.merkleDao().merkleRehash().blockingAwait()

        val out3 = groupHandleOne.declareHashesMerkle(clientSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).ignoreElement()
        val out4 = groupHandleTwo.declareHashesMerkle(serverSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).toObservable()
            .mergeWith(out3)
            .lastOrError()
            .blockingGet()
            .toMutableList()

        println("got out4 ${out4.size}")


        val nr2 = ds2.merkleDao().getDefaultRoot().blockingGet()


        val o2 = ds2.merkleDao().getTopRandomExcludingHash(nr2.id!!, 500, out4).blockingGet()

        assertEquals(1, o2.size)
    }


    @Test
    fun merkleSyncSame() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1))
            .setApplication("fmef")
            .build()
        datastore1.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        datastore2.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        ds1.merkleDao().merkleRehash().blockingAwait()
        ds2.merkleDao().merkleRehash().blockingAwait()

        val out1 = groupHandleOne.declareHashesMerkle(clientSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF)
            .doOnSuccess { out1 -> println("got out1: ${out1.map { v -> v.toByteString() }}") }
            .ignoreElement()
        val out2 = groupHandleTwo.declareHashesMerkle(serverSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF)
            .toFlowable()
            .mergeWith(out1)
            .lastOrError()
            .blockingGet()
            .toMutableList()

        val nr = ds1.merkleDao().getDefaultRoot().blockingGet()
        val root2 = ds2.merkleDao().getDefaultRoot().blockingGet()
        assertNotNull(root2.hash)
        assertNotNull(nr.hash)
        assertNotEquals(root2.hash, nr.hash)
        assertNotEquals(out2.size, 0)

        println("got out2 ${out2.map { v -> v.toByteString() }}")
        val o = ds1.merkleDao().getTopRandomExcludingHash(nr.id!!, 500, out2).blockingGet()

        assertEquals(0, o.size)
    }

    @Test
    fun merkleSyncReverse() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1))
            .setApplication("fmef")
            .build()
        datastore1.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        val apiMessage2 = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(2))
            .setApplication("fmef")
            .build()
        datastore2.insertAndHashFileFromApi(apiMessage2, DEFAULT_BLOCKSIZE, "").blockingAwait()

        ds1.merkleDao().merkleRehash().blockingAwait()
        ds2.merkleDao().merkleRehash().blockingAwait()

        val out1 = groupHandleOne.declareHashesMerkle(clientSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF)
            .doOnSuccess { out1 -> println("got out1: ${out1.map { v -> v.toByteString() }}") }
            .ignoreElement()
        val out2 = groupHandleTwo.declareHashesMerkle(serverSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF)
            .toFlowable()
            .mergeWith(out1)
            .lastOrError()
            .blockingGet()
            .toMutableList()

        val nr = ds1.merkleDao().getDefaultRoot().blockingGet()
        val root2 = ds2.merkleDao().getDefaultRoot().blockingGet()
        assertNotEquals(root2.hash, nr.hash)

        println("got out2 ${out2.map { v -> v.toByteString() }}")
        val o = ds1.merkleDao().getTopRandomExcludingHash(nr.id!!, 500, out2).blockingGet()

        assertEquals(1, o.size)

        val out3 = groupHandleTwo.declareHashesMerkle(clientSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).ignoreElement()
        val out4 = groupHandleOne.declareHashesMerkle(serverSocket, Scatterbrain.DeclareHashesMode.MERKLEPROOF).toObservable()
            .mergeWith(out3).firstOrError()
            .blockingGet()
            .toMutableList()

        println("got out4 ${out4.size}")


        val nr2 = ds1.merkleDao().getDefaultRoot().blockingGet()


        val o2 = ds1.merkleDao().getTopRandomExcludingHash(nr2.id!!, 500, out4).blockingGet()

        assertEquals(1, o2.size)
    }

    @Test
    fun ackPacketWorks() {
        val ack = AckPacket.newBuilder(true)
            .build()

        testSerialize(AckPacketParser.parser, ack) { parsed ->
            assert(parsed.success)
        }
        val status = -100
        val message = "fmef"
        val ack2 = AckPacket.newBuilder(false)
            .setStatus(status)
            .setMessage(message)
            .build()

        testSerialize(AckPacketParser.parser, ack2) { parsed ->
            assert(!parsed.success)
            assert(parsed.message == message)
            assert(parsed.status == status)
        }
    }

    @Test
    fun advertisePacketWorks() {
        val provides = listOf(Provides.WIFIP2P)
        val packet = AdvertisePacket.newBuilder()
            .setProvides(provides)
            .build()
        assert(packet != null)
        testSerialize(AdvertisePacketParser.parser, packet!!) { parsed ->
            assert(parsed.provides == provides)
        }
    }


    @Test
    fun blockHeaderPacketWorks() {

        for (toDisk in arrayOf(true, false)) {
            for (endOfStream in arrayOf(true, false)) {
                val toFingerprint = UUID.randomUUID()
                val fromFingerprint = UUID.randomUUID()
                val application = "fmef"
                val sig = ByteArray(Sign.ED25519_BYTES)
                val sessionId = 4
                val mime = "application/octet-stream"
                val extension = "exe"
                val hashes = listOf(ByteString.copyFrom(ByteArray(Hash.BYTES)))

                val oldHeader = BlockHeaderPacket.newBuilder()
                    .setToFingerprint(toFingerprint)
                    .setFromFingerprint(fromFingerprint)
                    .setApplication(application)
                    .setSig(sig)
                    .setToDisk(toDisk)
                    .setSessionID(sessionId)
                    .setMime(mime)
                    .setExtension(extension)
                    .setHashes(hashes)
                    .setEndOfStream(endOfStream)
                    .build()
                testSerialize(BlockHeaderPacketParser.parser, oldHeader) { header ->
                    if (endOfStream) {
                        assert(header.isEndOfStream)
                    } else {
                        assert(header.toFingerprint[0] == toFingerprint)
                        assert(header.fromFingerprint[0] == fromFingerprint)
                        assert(header.application == application)
                        assert(header.autogenFilename.isNotEmpty())
                        assert(header.signature.contentEquals(sig))
                        assert(header.isFile == toDisk)
                        assert(header.mime == mime)
                        assert(header.extension == extension)
                        assert(header.isValidFilename)
                        assert(header.sessionID == sessionId)
                        header.hashes.contentEquals(oldHeader.hashes)
                    }

                }
            }
        }
    }

    @Test
    fun blockSequencePacketWorks() {
        val data = ByteArray(256)
        Random().nextBytes(data)
        for (end in arrayOf(false, true)) {
            for (x in 0..3) {
                val blockSeq = BlockSequencePacket.newBuilder()
                    .setData(ByteString.copyFrom(data))
                    .setSequenceNumber(x)
                    .setEnd(end)
                    .build()
                testSerialize(BlockSequencePacketParser.parser, blockSeq) { packet ->
                    assert(packet.sequenceNum == x)
                    assert(packet.data.contentEquals(data))
                    assert(packet.calculateHash().isNotEmpty())
                    assert(packet.isEnd == end)
                }
            }
        }
    }

    @Test
    fun declareHashesPacketWorks() {
        val hashes = listOf(ByteArray(Hash.BYTES))
        Random().nextBytes(hashes[0])

        val declareHashes = DeclareHashesPacket.newBuilder()
            .setHashesByte(hashes)
            .build()
        testSerialize(DeclareHashesPacketParser.parser, declareHashes) { packet ->
            assert(packet.hashes.size == hashes.size)
            assert(packet.hashes[0].contentEquals(hashes[0]))
        }

        val optOut = DeclareHashesPacket.newBuilder()
            .optOut()
            .build()

        testSerialize(DeclareHashesPacketParser.parser, optOut) { packet ->
            assert(packet.optout)
        }
    }

    @Test
    fun electLeaderPacketWorks() {
        val hash = ByteArray(Hash.BYTES)
        val provides = Provides.WIFIP2P
        val tiebreaker = UUID.randomUUID()
        Random().nextBytes(hash)


        val electLeaderPacket = ElectLeaderPacket.newBuilder(UUID.randomUUID())
            .setProvides(provides)
            .setTiebreaker(tiebreaker)
            .build()

        val hashedPacket = ElectLeaderPacket.newBuilder(UUID.randomUUID())
            .setProvides(provides)
            .setTiebreaker(tiebreaker)
            .enableHashing()
            .build()

        testSerialize(ElectLeaderPacketParser.parser, electLeaderPacket) { packet ->
            assert(!packet.isHashed)
            assert(packet.provides == provides)
            assert(packet.tieBreak == tiebreaker)
            testSerialize(ElectLeaderPacketParser.parser, hashedPacket) { hpacket ->
                assert(hpacket.isHashed)
                assert(hpacket.verifyHash(packet))
            }
        }
    }

    @Test
    fun identityPacketWorks() {
        val name = "mr. fmef"
        val keypair = ApiIdentity.newPrivateKey()
        val apiIdentity = ApiIdentity.newBuilder()
            .sign(keypair)
            .setName(name)
            .build()
        val identityPacket = IdentityPacket.newBuilder()
            .setScatterbrainPubkey(ByteString.copyFrom(keypair.publickey))
            .setName(name)
            .setSig(apiIdentity.identity.sig)
            .build()
        assert(identityPacket != null)
        testSerialize(IdentityPacketParser.parser, identityPacket!!) { packet ->
            assert(!packet.isEnd)
            assert(packet.name == name)
            assert(packet.uuid != null)
            assert(packet.fingerprint != null)
            assert(packet.fingerprint!!.isNotEmpty())
            assert(packet.verifyed25519(keypair.publickey))
        }

        val endPacket = IdentityPacket.newBuilder().setEnd().build()
        assert(endPacket != null)
        testSerialize(IdentityPacketParser.parser, endPacket!!) { packet ->
            assert(packet.isEnd)
        }
    }

    @Test
    fun luidPacketWorks() {
        val luid = UUID.randomUUID()
        val protoVersion = 5
        val luidPacket = LuidPacket.newBuilder()
            .setLuid(luid)
            .build()

        testSerialize(LuidPacketParser.parser, luidPacket) { packet ->
            assert(packet.luidVal == luid)
        }

        val hashedPacket = LuidPacket.newBuilder()
            .setLuid(luid)
            .enableHashing(protoVersion)
            .build()

        testSerialize(LuidPacketParser.parser, hashedPacket) { packet ->
            assert(packet.verifyHash(luidPacket))
            assert(packet.protoVersion == protoVersion)
        }
    }

    @Test
    fun routingMetadataPacketWorks() {
        //note we only need to support "empty" packets for now
        val emptyPacket = RoutingMetadataPacket.newBuilder().setEmpty().build()

        testSerialize(RoutingMetadataPacketParser.parser, emptyPacket) { packet ->
            assert(packet.isEmpty)
        }
    }

    @Test
    fun upgradePacketWorks() {
        val metadata = mapOf("fmef" to "fmefval")
        val provides = Provides.WIFIP2P
        val sessionid = 5
        val upgradePacket = net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket.newBuilder(Scatterbrain.Role.SEME)
            .setMetadata(metadata)
            .setProvides(provides)
            .setSessionID(sessionid)
            .build()
        assert(upgradePacket != null)
        testSerialize(UpgradePacketParser.parser, upgradePacket!!) { packet ->
            packet.metadata.forEach { (t, u) ->
                assert(metadata[t] == u)
            }
            assert(packet.provides == provides)
            assert(packet.sessionID == sessionid)
        }
    }
}