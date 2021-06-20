package net.ballmerlabs.uscatterbrain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import io.reactivex.plugins.RxJavaPlugins
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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

}