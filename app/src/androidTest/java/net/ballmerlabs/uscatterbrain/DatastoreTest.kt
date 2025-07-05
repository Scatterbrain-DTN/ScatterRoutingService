package net.ballmerlabs.uscatterbrain

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.ByteString
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.subjects.PublishSubject
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.db.*
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle
import net.ballmerlabs.uscatterbrain.db.migration.Migrate9
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.compare
import net.ballmerlabs.uscatterbrain.network.proto.*
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.toBytes
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeoutException

@RunWith(AndroidJUnit4ClassRunner::class)
class DatastoreTest {

    private lateinit var ctx: Context
    private lateinit var datastore: ScatterbrainDatastore
    private lateinit var database: Datastore
    private val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test"))


    @Rule
    @JvmField
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        Datastore::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory()
    )

    @ExperimentalCoroutinesApi
    @Before
    fun init() {
        ctx = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(ctx, Datastore::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .fallbackToDestructiveMigration()
            .build()


            //    database.clearAllTables()

        val prefs = RouterPreferencesImpl(
            ctx.dataStore
        )

        datastore = ScatterbrainDatastoreImpl(
            ctx,
            database,
            scheduler,
            scheduler,
            prefs,
            { object : ScatterbrainScheduler {
                override fun start() {

                }

                override fun stop(): Boolean {
                    return true
                }

                override fun pauseScan() {

                }

                override fun unpauseScan() {

                }

                override fun broadcastTransactionResult(transactionStats: HandshakeResult): Completable {
                    return Completable.complete()
                }

                override fun acquireWakelock() {

                }

                override fun releaseWakeLock() {

                }

                override fun authorizeDesktop(
                    fingerprint: ByteArray,
                    authorize: Boolean,
                ): Completable {
                    return Completable.complete()
                }

                override fun startDesktopServer(name: String): Completable {
                    return Completable.complete()
                }

                override fun confirmIdentityImport(handle: UUID, identity: UUID): Completable {
                    return Completable.complete()
                }

                override fun stopDesktopServer() {

                }

                override fun broadcastIdentities(identities: List<IdentityPacket>): Completable {
                    return Completable.complete()
                }

                override fun broadcastMessages(messages: List<DbMessage>): Completable {
                    return Completable.complete()
                }

                override val isDiscovering: Boolean
                    get() = true
                override val isPassive: Boolean
                    get() = false

            } },
            BroadcasterImpl(ctx),
            mock {  }
        )
        database.clearAllTables()
    }

    @Test
    fun multiRoot() {
        val root1 = database.merkleDao().getDefaultRoot().blockingGet()
        val root2 = database.merkleDao().getDefaultRoot().blockingGet()
        assertEquals(root1.id, root2.id)
        assertEquals(database.merkleDao().getRootsRandom().blockingGet().size, 1)
    }

    @Test
    fun bundleInsert() {

        val child = MerkleBundle(
            hash = LibsodiumInterface.merkleHash(byteArrayOf(3,2,1))
        )
        val out = database.merkleDao().insertBundleEntity(child).blockingGet()

        val bundle = MerkleBundle(
            hash = LibsodiumInterface.merkleHash(byteArrayOf(1, 2, 3)),
            childOne = out
        )

        database.merkleDao().insertBundleEntity(bundle).blockingGet()


        val size = database.merkleDao().getRoots().blockingGet().size
        println("got size $size")
        assertEquals(size, 1)
    }


    @Test
    fun insertMessage() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1))
            .setApplication("fmef")
            .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        assertEquals(datastore.getApiMessages("fmef").blockingGet().size, 1)
        assertEquals(database.merkleDao().getDirty().blockingGet().size, 0)
        val root = database.merkleDao().getRoots().blockingGet()[0]
        assertEquals(database.merkleDao().getRootsRandom().blockingGet().size, 1)

        assertEquals(
            database.merkleDao().getMessagesForBundleRecursive(root.id!!).blockingGet().size,
            1
        )
    }


    @Test
    fun deleteMessage() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1))
            .setApplication("fmef")
            .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        val root = database.merkleDao().getRoots().blockingGet()[0]
        assertEquals(database.merkleDao().getRootsRandom().blockingGet().size, 1)

        val message = database.scatterMessageDao().getAllMessages()[0]

        assertEquals(
            database.merkleDao().getMessagesForBundleRecursive(root.id!!).blockingGet().size,
            1
        )

        database.scatterMessageDao().delete(message).blockingAwait()

        assertEquals(
            database.merkleDao().getMessagesForBundleRecursive(root.id!!).blockingGet().size,
            0
        )

    }


    @Test
    fun insertionPointWithoutDb() {

        val gh = LibsodiumInterface.merkleHash(byteArrayOf(1, 2 ,3))

        val root = database.merkleDao().getDefaultRoot().blockingGet()
        val isp1 = database.merkleDao().getInsertionPoint(gh, root.id!!.toLong(), 0).blockingGet()

        val ndisp1 = database.merkleDao().getInsertionPointWithoutDb(gh, root.id!!.toLong(), 0)

        assertEquals(isp1, ndisp1)

        assertEquals(database.merkleDao().getRootsRandom().blockingGet().size, 1)

        for (x in 0..5) {
            val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1, 2, 3))
                .setApplication("fmef")
                .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }


        val gh2 = LibsodiumInterface.merkleHash(byteArrayOf(2, 3 ,4, 5))
        val isp = database.merkleDao().getInsertionPoint(gh2, root.id!!.toLong(), 0).blockingGet()

        val ndisp = database.merkleDao().getInsertionPointWithoutDb(gh, root.id!!.toLong(), 0)

        assertEquals(ndisp, isp)

    }


    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun insertMessageHash() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1, 2, 3))
            .setApplication("fmef")
            .build()
        for (x in 0..1) {
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }
        val message = datastore.getTopRandomMessages(1, listOf()).blockingFirst()
        println("globalhash ${message.entity!!.message.fileGlobalHash.toHexString()}")
        val size = datastore.getApiMessages("fmef").blockingGet().size
        println("size $size")
        assertEquals(size, 1)
        assertEquals(database.merkleDao().getDirty().blockingGet().size, 0)
        val root = database.merkleDao().getRootsRandom().blockingGet()

        for (x in root) {
            println("root: $x")
        }
//
//        val file = File(ctx.externalCacheDir!!.path + "/output.sqlite")
//        val datastoreFile = File(database.openHelper.readableDatabase.path!!)
//        val f = datastoreFile.inputStream()
//        f.copyTo(file.outputStream())
//
//        println("output: $file")
        
        assertEquals(root.size, 1)

        assertEquals(
            database.merkleDao().getMessagesForBundleRecursive(root[0].id!!).blockingGet().size,
            1
        )
    }


    @Test
    fun getDirtyNodes() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(0, 2, 4))
            .setApplication("fmef")
            .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        val root = database.merkleDao().getDefaultRoot().blockingGet()
        val dirty = database.merkleDao().getDirtyNodes(root.id!!).blockingGet()

        assertEquals(dirty.size, 0)
    }


    @Test
    fun merkleTopRandom() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(0, 2, 4))
            .setApplication("fmef")
            .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        database.merkleDao().merkleRehash().blockingAwait()

        var root = database.merkleDao().getDefaultRoot().blockingGet()
        val messages = database.merkleDao().getTopRandomExcludingHash(root.id!!, 200, listOf()).blockingGet()

        assertEquals(messages.size, 1)

        val apiMessage2 = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(0, 2, 8))
            .setApplication("fmef")
            .build()
        datastore.insertAndHashFileFromApi(apiMessage2, DEFAULT_BLOCKSIZE, "").blockingAwait()

        database.merkleDao().merkleRehash().blockingAwait()

        root = database.merkleDao().getDefaultRoot().blockingGet()

        val remote = PublishSubject.create<ByteArray>()

        val iter = database.merkleDao().getHubs(root, remote.toFlowable(BackpressureStrategy.BUFFER)).hubs
            .doOnNext { i -> remote.onNext(UUID.randomUUID().toBytes()) }
            .doFinally { remote.onComplete() }
            .toList().blockingGet()


        assert(iter.last().last)
        assert(!iter.first().last)

        //println("got hubs ${iter.size}")

        val control = database.scatterMessageDao().getTopRandomExcludingHash(100, listOf()).blockingGet()
        assertEquals(control.size, 2)

        val control2 = database.scatterMessageDao().getTopRandomExcludingHash(100, listOf(control[1].message.fileGlobalHash)).blockingGet()

        assertEquals(control2.size, 1)

        val messages2 = database.merkleDao().getTopRandomExcludingHash(root.id!!, 100, listOf()).blockingGet()
        assertEquals(messages2.size, 2)

        val testBundles = database.merkleDao().getAllBundles()

        val excludeBundles = database.merkleDao().testBundlesExcludingHash(listOf(iter[1].bundle.hash!!, iter[2].bundle.hash!!))

        println("testBundles = ${testBundles.size} excludeBundles = ${excludeBundles.size}")

        val messages3 = database.merkleDao().getTopRandomExcludingHash(root.id!!, 100, listOf(iter[1].bundle.hash!!)).blockingGet()
        for (m in messages3) {
            println(m)
        }
        assertEquals(messages3.size, 1)
    }

    @Test
    fun getNextHub() {
        val b1 = MerkleBundle(hash = UUID.randomUUID().toBytes())
        val b2 = MerkleBundle(hash = UUID.randomUUID().toBytes())
        val b3 = MerkleBundle(hash = UUID.randomUUID().toBytes())
        val b4 = MerkleBundle(hash = UUID.randomUUID().toBytes())
        val b5 = MerkleBundle(hash = UUID.randomUUID().toBytes())
        val b1i = database.merkleDao().insertBundleEntity(b1).blockingGet()
        println("b1i $b1i")
        b2.childOne = b1i
        val b3i = database.merkleDao().insertBundleEntity(b3).blockingGet()
        println("b3i $b3i")
        b2.childTwo = b3i
        val b2i = database.merkleDao().insertBundleEntity(b2).blockingGet()
        println("b2i $b2i")
        b4.childOne = b2i
        val b4i = database.merkleDao().insertBundleEntity(b4).blockingGet()
        println("b4i $b4i")
        b5.childTwo = b4i
        val b5i = database.merkleDao().insertBundleEntity(b5).blockingGet()
        println("b5i $b5i")
        val test = database.merkleDao().getNextHub(b5i)!!

        assertEquals(test.id, b2i)

        b5.id = b5i

        val remote = PublishSubject.create<ByteArray>()
        database.merkleDao().merkleRehash().blockingAwait()
        val hubs = database.merkleDao().getHubs(
            database.merkleDao().getBundle(b5i),
            remote.toFlowable(BackpressureStrategy.BUFFER)
        ).hubs
            .doOnNext { i -> remote.onNext(UUID.randomUUID().toBytes()) }
            .doFinally { remote.onComplete() }
            .toList().blockingGet()

        for (item in hubs) {
            println(item)
        }
        assertEquals(hubs.size, 4)
        val ids = hubs.map { v -> v.bundle.id }
        assert(ids.contains(b5i))
        assert(ids.contains(b3i))
        assert(ids.contains(b1i))
        assert(ids.contains(b2i))
    }



    @Test
    fun duplicateRoot() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1, 2, 3))
            .setApplication("fmef")
            .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()

        database.merkleDao().getDefaultRoot().blockingGet()
        val root = database.merkleDao().getRootsRandom().blockingGet()

        assertEquals(root.size, 1)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun insertMessageReverse() {

        val count = 4
        for (x in 0..count) {
            val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(x.toByte(), 2, 3))
                .setApplication("fmef")
                .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }

        database.merkleDao().merkleRehash().blockingAwait()

        val root = database.merkleDao().getRootsRandom().blockingGet()

        assertEquals(root.size, 1)

        assertEquals(
            database.merkleDao().getMessagesForBundleRecursive(root[0].id!!).blockingGet().size,
            count + 1
        )

        val firstSize = datastore.getTopRandomMessages(1000, listOf()).toList().blockingGet()

        val firstBundles = database.merkleDao().getAllBundles()

        database.clearAllTables()

        for (x in count downTo 0) {
            val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(x.toByte(), 2, 3))
                .setApplication("fmef")
                .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }

        database.merkleDao().merkleRehash().blockingAwait()

        var prev = database.merkleDao().getDefaultRoot().blockingGet()
        for (x in 0..10) {
            val rand = database.merkleDao().getDefaultRoot().blockingGet()
            assertEquals(prev.id, rand.id)
            prev = rand
        }


        val secondBundles = database.merkleDao().getAllBundles()

        assertEquals(firstBundles.size, secondBundles.size)

       // assertEquals(firstBundles.map { v -> v.hash!!.toHexString() }, secondBundles.map { v -> v.hash!!.toHexString() } )

        val secondSize = datastore.getTopRandomMessages(1000, listOf()).toList().blockingGet()

        val secondHashes = secondSize.map { v -> getGlobalHash(v.headerPacket.hashList) }
            .sortedWith { v, n -> v.compare(n) }
            .map { v -> v.toHexString() }
            .joinToString(", ")
        val firstHashes = firstSize.map { v -> getGlobalHash(v.headerPacket.hashList) }
            .sortedWith { v, n -> v.compare(n) }
            .map { v -> v.toHexString() }
            .joinToString(", ")
        println("firstHashes $firstHashes")
        println("secondHashes $secondHashes")

        assertEquals(firstSize.size, secondSize.size)

        val root2 = database.merkleDao().getRootsRandom().blockingGet()

        val diff = firstBundles.toSet().intersect(secondBundles.toSet())
        assertEquals(diff, setOf<MerkleBundle>())
        assertEquals(root2.size, 1)
        assert(!root[0].dirty)
        assertNotNull(root[0].hash)
        assertNotEquals(root[0].hash!!.size, 0)
        val dirty = database.merkleDao().getDirty().blockingGet()
        assertEquals(dirty.size, 0)

        val root1hash = root[0].hash
        val root2hash = root2[0].hash

        println("root1=${root1hash!!.toHexString()} root2=${root2hash!!.toHexString()}")

        assert(root1hash.contentEquals(root2hash))
    }


    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun insertMessageReverseLastByte() {

        val count = 0xf
        for (x in 0..count) {
            val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(0xf, 2, 3, x.toByte()))
                .setApplication("fmef")
                .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }

        database.merkleDao().merkleRehash().blockingAwait()

        val root = database.merkleDao().getRootsRandom().blockingGet()

        assertEquals(root.size, 1)

        assertEquals(
            database.merkleDao().getMessagesForBundleRecursive(root[0].id!!).blockingGet().size,
            count + 1
        )

        val firstSize = datastore.getTopRandomMessages(1000, listOf()).toList().blockingGet()

        val firstBundles = database.merkleDao().getAllBundles()

        database.clearAllTables()

        for (x in count downTo 0) {
            val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(0xf, 2, 3, x.toByte()))
                .setApplication("fmef")
                .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }

        database.merkleDao().merkleRehash().blockingAwait()

        var prev = database.merkleDao().getDefaultRoot().blockingGet()
        for (x in 0..10) {
            val rand = database.merkleDao().getDefaultRoot().blockingGet()
            assertEquals(prev.id, rand.id)
            prev = rand
        }


        val secondBundles = database.merkleDao().getAllBundles()

        assertEquals(firstBundles.size, secondBundles.size)

        // assertEquals(firstBundles.map { v -> v.hash!!.toHexString() }, secondBundles.map { v -> v.hash!!.toHexString() } )

        val secondSize = datastore.getTopRandomMessages(1000, listOf()).toList().blockingGet()

        val secondHashes = secondSize.map { v -> getGlobalHash(v.headerPacket.hashList) }
            .sortedWith { v, n -> v.compare(n) }
            .map { v -> v.toHexString() }
            .joinToString(", ")
        val firstHashes = firstSize.map { v -> getGlobalHash(v.headerPacket.hashList) }
            .sortedWith { v, n -> v.compare(n) }
            .map { v -> v.toHexString() }
            .joinToString(", ")
        println("firstHashes $firstHashes")
        println("secondHashes $secondHashes")

        assertEquals(firstSize.size, secondSize.size)

        val root2 = database.merkleDao().getRootsRandom().blockingGet()

        val diff = firstBundles.toSet().intersect(secondBundles.toSet())
        assertEquals(diff, setOf<MerkleBundle>())
        assertEquals(root2.size, 1)
        assert(!root[0].dirty)
        assertNotNull(root[0].hash)
        assertNotEquals(root[0].hash!!.size, 0)
        val dirty = database.merkleDao().getDirty().blockingGet()
        assertEquals(dirty.size, 0)

        val root1hash = root[0].hash
        val root2hash = root2[0].hash

        println("root1=${root1hash!!.toHexString()} root2=${root2hash!!.toHexString()}")

        assert(root1hash.contentEquals(root2hash))
    }


    @Test
    @Throws(IOException::class)
    fun migrate5To11() {
        var db = helper.createDatabase("fmefdb", 5)
            .apply {
                // Prepare for the next version.
                close()
            }

        // Re-open the database with version 2 and provide
        // MIGRATION_1_2 as the migration process.
        db = helper.runMigrationsAndValidate("fmefdb", 11, true, Migrate9())

        // MigrationTestHelper automatically verifies the schema changes,
        // but you need to validate that the data was migrated properly.
    }

    @Test
    fun bitwiseEquivalencePrinciple() {
        for (y in 0..4) {
            val hash = byteArrayOf(y.toByte(), 2, 3)

            val buf = BitSet.valueOf(hash)

            for (x in 0 until hash.size * Byte.SIZE_BITS) {
                val test = if (database.merkleDao().testBitmask(hash, x.toLong())) {
                    0
                } else {
                    1
                }
                val test2 = if (database.merkleDao().isChildOne(hash, x.toLong())!!) {
                    0
                } else {
                    1
                }
                val control = if (buf.get(x)) {
                    1
                } else {
                    0
                }
                println("get $test $test2 $control")
                assertEquals(control, test)
                assertEquals(control, test2)
            }
        }

        for (h in listOf("f2", "fd")) {
            val hash = h.decodeHex().toByteArray()
            val buf = BitSet.valueOf(hash)

            val testBits = mutableListOf<Int>()
            val controlBits = mutableListOf<Int>()
            val test2Bits = mutableListOf<Int>()
            for (x in 0 until hash.size * Byte.SIZE_BITS) {
                val test = if (database.merkleDao().testBitmask(hash, x.toLong())) {
                    0
                } else {
                    1
                }
                val hex = database.merkleDao().testBitmaskHex(hash, x)
                val test2 = if (database.merkleDao().isChildOne(hash, x.toLong())!!) {
                    0
                } else {
                    1
                }
                val control = if (buf.get(x)) {
                    1
                } else {
                    0
                }

                println("get $test $test2 $control hex=$hex")
                test2Bits.add(test2)
                testBits.add(test)
                controlBits.add(control)
            }

            assertEquals(test2Bits, controlBits)
            assertEquals(testBits, controlBits)
        }

    }

    @Test
    fun insertMessageWithFile() {
        val file = File.createTempFile("test", "jpeg", ctx.cacheDir)
        file.outputStream().write(byteArrayOf(1))
        val apiMessage = ScatterMessage.Builder.newInstance(file)
            .setApplication("fmef")
            .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        assertEquals(datastore.getApiMessages("fmef").blockingGet().size, 1)
        assertEquals(database.merkleDao().getDirty().blockingGet().size, 0)
        val root = database.merkleDao().getRoots().blockingGet()[0]

        assertEquals(
            database.merkleDao().getMessagesForBundleRecursive(root.id!!).blockingGet().size,
            1
        )

    }

    @Test
    fun insertAndDeleteMessage() {
        for (x in 0..5) {
            val file = File.createTempFile("test", "jpeg", ctx.cacheDir)
            file.outputStream().write(byteArrayOf(1))
            val apiMessage = ScatterMessage.Builder.newInstance(file)
                .setApplication("fmef")
                .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
            val m = datastore.getApiMessages("fmef").blockingGet()
            assert(m.size == 1)
            datastore.deleteMessage(m[0]).blockingAwait()
            assert(datastore.getApiMessages("fmef").blockingGet().size == 0)
            assertEquals(database.merkleDao().getDirty().blockingGet().size, 0)
        }
    }

    @Test
    @Throws(TimeoutException::class)
    fun pruneWorks() {
        val before = Date()
        val size = 10
        for (x in 0 until size) {
            val file = File.createTempFile("test", "jpeg", ctx.cacheDir)
            file.outputStream().write(byteArrayOf(x.toByte()))
            val apiMessage = ScatterMessage.Builder.newInstance(file)
                .setApplication("fmef")
                .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }
        assert(datastore.getApiMessages("fmef").blockingGet().size == size)
        datastore.trimDatastore(before, 0, 11).blockingAwait()
        assert(datastore.getApiMessages("fmef").blockingGet().size == size)
        datastore.trimDatastore(Date(), 0, 11).blockingAwait()
        assert(datastore.getApiMessages("fmef").blockingGet().size == 0)
    }


    @Test
    @Throws(TimeoutException::class)
    fun pruneWorksApi() {
        val size = 10
        for (x in 0 until size) {
            val file = File.createTempFile("test", "jpeg", ctx.cacheDir)
            file.outputStream().write(byteArrayOf(x.toByte()))
            val apiMessage = ScatterMessage.Builder.newInstance(file)
                .setApplication("com.fmef")
                .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "com.fmef")
                .blockingAwait()
        }
        assert(datastore.getApiMessages("com.fmef").blockingGet().size == size)
        datastore.trimDatastore("com.blerf", 0).blockingAwait()
        assert(datastore.getApiMessages("com.fmef").blockingGet().size == 10)
        datastore.trimDatastore("com.fmef", 0).blockingAwait()
        val res = datastore.getApiMessages("com.fmef").blockingGet().size
        println(res)
        assert(res == 0)
    }

    @Test
    fun dbMessageEquiv() {
        val size = 10
        val oldmessage =
            datastore.getTopRandomMessages(size, listOf()).reduce(
                mutableListOf<WifiDirectRadioModule.BlockDataStream>()
            ) { acc, v ->
                acc.add(v)
                acc
            }.blockingGet()
        assertEquals(oldmessage.size - 1, 0)
        for (x in 0 until size) {
            val apiMessage =
                ScatterMessage.Builder.newInstance(ctx, ByteBuffer.allocate(Int.SIZE_BYTES).apply {
                    putInt(x)
                }.array())
                    .setApplication("com.fmef")
                    .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "com.fmef")
                .blockingAwait()
        }

        val streams =
            datastore.getTopRandomMessages(size * 4, listOf())
                .reduce(
                    mutableListOf<WifiDirectRadioModule.BlockDataStream>()
                ) { acc, v ->
                    acc.add(v)
                    acc
                }.blockingGet()
        assertEquals(streams.size - 1, size)

        val messages = datastore.getApiMessages("com.fmef").blockingGet()
        assertEquals(messages.size, size)

        Observable.fromIterable(streams)
            .flatMapCompletable { v -> datastore.insertMessage(v) }
            .blockingAwait()


        val messages2 = datastore.getApiMessages("com.fmef").blockingGet()
        assertEquals(messages2.size, size)
    }


    @Test
    fun declareHashes() {
        val size = 10
        for (x in 0 until size) {
            val apiMessage =
                ScatterMessage.Builder.newInstance(ctx, ByteBuffer.allocate(Int.SIZE_BYTES).apply {
                    putInt(x)
                }.array())
                    .setApplication("com.fmef")
                    .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "com.fmef")
                .blockingAwait()
        }


        val streams =
            datastore.getTopRandomMessages(size * 4, listOf())
                .reduce(
                    mutableListOf<WifiDirectRadioModule.BlockDataStream>()
                ) { acc, v ->
                    acc.add(v)
                    acc
                }.blockingGet()
        assertEquals(streams.size - 1, size)


        val packet = DeclareHashesPacket.newBuilder()
            .setHashes(
                streams.map { v -> getGlobalHash(v.headerPacket.hashList) }
                    .map { v -> ByteString.copyFrom(v) }
            ).build()

        val newstreams =
            datastore.getTopRandomMessages(size * 4, packet.hashes).reduce(
                mutableListOf<WifiDirectRadioModule.BlockDataStream>()
            ) { acc, v ->
                acc.add(v)
                acc
            }.blockingGet()
        assertEquals(newstreams.size - 1, 0)
    }

}