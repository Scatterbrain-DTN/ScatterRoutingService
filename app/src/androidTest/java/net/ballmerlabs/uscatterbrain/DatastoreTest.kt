package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import io.reactivex.plugins.RxJavaPlugins
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.*
import net.ballmerlabs.uscatterbrain.db.migration.Migrate6
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.jvm.Throws

@RunWith(AndroidJUnit4ClassRunner::class)
class DatastoreTest {

    private lateinit var ctx: Context
    private lateinit var datastore: ScatterbrainDatastore
    private lateinit var database: Datastore
    private val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory())

    @ExperimentalCoroutinesApi
    @Before
    fun init() {
        ctx = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(ctx, Datastore::class.java)
                .addMigrations(Migrate6())
                .build()
        val prefs = RouterPreferencesImpl(
                ctx.getSharedPreferences(RoutingServiceComponent.SHARED_PREFS, Context.MODE_PRIVATE)
        )

        datastore = ScatterbrainDatastoreImpl(
                ctx,
                database,
                scheduler,
                prefs
        )
        database.clearAllTables()
    }


    @Test
    fun insertMessage() {
        val apiMessage = ScatterMessage.newBuilder()
                .setApplication("fmef")
                .setBody(byteArrayOf(1))
                .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE).blockingAwait()
        assert(datastore.getApiMessages("fmef").blockingGet().size == 1)
    }


    @Test
    fun insertMessageWithFile() {
        val file = File.createTempFile("test", "jpeg", ctx.cacheDir)
        file.outputStream().write(byteArrayOf(1))
        val apiMessage = ScatterMessage.newBuilder()
                .setApplication("fmef")
                .setFile(file)
                .build()
        datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE).blockingAwait()
        assert(datastore.getApiMessages("fmef").blockingGet().size == 1)
    }

    @Test
    fun insertAndDeleteMessage() {
        for (x in 0..5) {
            val file = File.createTempFile("test", "jpeg", ctx.cacheDir)
            file.outputStream().write(byteArrayOf(1))
            val apiMessage = ScatterMessage.newBuilder()
                    .setApplication("fmef")
                    .setFile(file)
                    .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE).blockingAwait()
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
            val apiMessage = ScatterMessage.newBuilder()
                    .setApplication("fmef")
                    .setFile(file)
                    .build()
            datastore.insertAndHashFileFromApi(apiMessage, DEFAULT_BLOCKSIZE).blockingAwait()
        }
        Log.e("debug", "size: ${datastore.getApiMessages("fmef").blockingGet().size}")
        assert(datastore.getApiMessages("fmef").blockingGet().size == size)
        datastore.trimDatastore(before).blockingAwait()
        assert(datastore.getApiMessages("fmef").blockingGet().size == size)
        datastore.trimDatastore(Date()).blockingAwait()
        assert(datastore.getApiMessages("fmef").blockingGet().size == 0)
    }

}