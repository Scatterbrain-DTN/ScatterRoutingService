package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.rxCompletable
import kotlinx.coroutines.rx2.rxSingle
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.toPacket
import net.ballmerlabs.uscatterbrain.util.scatterLog
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.RadioClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Flow
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MeshtasticRadioModuleImpl @Inject constructor(
    val datastore: ScatterbrainDatastore,
    val sessionBuilder: MeshtasticSessionSubcomponent.Builder,
    val advertiser: Advertiser,
    val database: Datastore,
    val radioClient: RadioClient,
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) val timeoutScheduler: Scheduler,
    @Named(MeshtasticConnectionSubcomponent.NamedSchedulers.COROUTINE_CONTEXT) val coroutineScope: CoroutineScope
) : MeshtasticRadioModule {


    private val log by scatterLog()


    private val currentTransactions = ConcurrentHashMap<Int, MeshtasticSessionSubcomponent>()
    private val backlog = ConcurrentLinkedQueue<MeshtasticSessionSubcomponent>()

    override fun popBacklog(): MeshtasticSessionSubcomponent? {
        return backlog.poll()
    }

    override fun startBacklog(from: Int) {
        val session = currentTransactions.compute(from) { k, v ->
            when (v) {
                null -> sessionBuilder.id(k).build()
                else -> v
            }
        }!!

        backlog.add(session)
    }

    override fun startSession(from: Int): MeshtasticSessionSubcomponent {
        val session = currentTransactions.compute(from) { k, v ->
            when (v) {
                null -> sessionBuilder.id(k).build()
                else -> v
            }
        }!!

        log.v("startSession size=${currentTransactions.size} from=$from")

        return if (currentTransactions.size > MAX_SESSIONS) {
            backlog.add(session)
            session
        } else {
            session
        }
    }

    override fun stopSession(from: Int) {
        currentTransactions.remove(from)
    }

    override fun getSessionCount(): Int {
        return currentTransactions.size
    }

    override suspend fun getConnection(): Flowable<ConnectionState> {
        return radioClient.connection.asFlowable()
    }


    override suspend fun connect() {
        log.w("attempting meshtastic connection")
        radioClient.connect()
        log.w("meshtastic connected!")
    }



    override suspend fun handlePackets() {
        radioClient.packets.collect { packet ->
            startSession(packet.from).state().handlePacket(packet).collect { reply ->
                radioClient.send(reply.toPacket(packet.from))
            }
        }
    }

    override suspend fun handshake(){
        log.w("explicit handshake!")
    }
}