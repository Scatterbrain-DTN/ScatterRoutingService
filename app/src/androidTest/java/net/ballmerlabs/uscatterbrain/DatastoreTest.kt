package net.ballmerlabs.uscatterbrain

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.ByteString
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.*
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.db.migration.Migrate9
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import org.junit.Assert.*;
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
import net.ballmerlabs.uscatterbrain.network.proto.*
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
                .fallbackToDestructiveMigration()
                .build()
        val prefs = RouterPreferencesImpl(
                ctx.dataStore
        )

        datastore = ScatterbrainDatastoreImpl(
                ctx,
                database,
                scheduler,
            scheduler,
                prefs,
                mock {  },
            mock {  }

        )
        database.clearAllTables()
    }


    @Test
    fun insertMessage() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1))
                .setApplication("fmef")
                .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE,"").blockingAwait()
        assert(datastore.getApiMessages("fmef").blockingGet().size == 1)
    }


    @Test
    fun insertMessageHash() {
        val apiMessage = ScatterMessage.Builder.newInstance(ctx, byteArrayOf(1, 2, 3))
            .setApplication("fmef")
            .build()
        for(x in 0..5) {
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        }
        val size = datastore.getApiMessages("fmef").blockingGet().size
        println("size $size")
        assert(size == 1)
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
    fun insertMessageWithFile() {
        val file = File.createTempFile("test", "jpeg", ctx.cacheDir)
        file.outputStream().write(byteArrayOf(1))
        val apiMessage = ScatterMessage.Builder.newInstance(file)
                .setApplication("fmef")
                .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "").blockingAwait()
        assert(datastore.getApiMessages("fmef").blockingGet().size == 1)
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
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE, "com.fmef").blockingAwait()
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
            datastore.getTopRandomMessages(size, DeclareHashesPacket.newBuilder().build()).reduce(
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
            datastore.getTopRandomMessages(size*4, DeclareHashesPacket.newBuilder().build()).reduce(
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
            datastore.getTopRandomMessages(size*4, DeclareHashesPacket.newBuilder().build()).reduce(
                mutableListOf<WifiDirectRadioModule.BlockDataStream>()
            ) { acc, v ->
                acc.add(v)
                acc
            }.blockingGet()
        assertEquals(streams.size - 1, size)


        val packet = DeclareHashesPacket.newBuilder()
            .setHashes(
                streams.map { v -> getGlobalHash(v.headerPacket.hashList) }.map { v -> ByteString.copyFrom(v) }
            ).build()

        val newstreams =
            datastore.getTopRandomMessages(size*4, packet).reduce(
                mutableListOf<WifiDirectRadioModule.BlockDataStream>()
            ) { acc, v ->
                acc.add(v)
                acc
            }.blockingGet()
        assertEquals(newstreams.size - 1, 0)
    }

}