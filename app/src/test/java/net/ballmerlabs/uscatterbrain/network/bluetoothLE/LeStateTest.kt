package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.schedulers.Schedulers
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class LeStateTest {


    private lateinit var state: LeState

    @Mock
    private lateinit var advertiser: Advertiser

    @Mock
    private lateinit var gattServer: ManagedGattServer

    @Mock
    private lateinit var transactionFactory: ScatterbrainTransactionFactory

    private val scheduler = Schedulers.io()

    @Before
    fun init() {
        MockitoAnnotations.openMocks(this)
        state = LeStateImpl(
            clientScheduler = scheduler,
            factory = transactionFactory,
            advertiser = advertiser,
            server = { gattServer }
        )
    }

    @Test
    fun lockOnce() {
        val luid = UUID.randomUUID()
        assert(state.transactionLockAccquire(luid))
        assert(state.transactionUnlock(luid))
    }

    @Test
    fun lockTwice() {
        val luid = UUID.randomUUID()
        assert(state.transactionLockAccquire(luid))
        assert(!state.transactionLockAccquire(luid))
        assert(state.transactionUnlock(luid))
        assert(state.transactionUnlock(luid))
        assert(state.transactionLockAccquire(luid))
    }


    @After
    fun cleanup() {
        Mockito.validateMockitoUsage()
    }
}