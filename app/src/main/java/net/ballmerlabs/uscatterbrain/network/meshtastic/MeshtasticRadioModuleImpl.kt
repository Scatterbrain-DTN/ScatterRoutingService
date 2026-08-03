package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Completable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MeshtasticRadioModuleImpl @Inject constructor(
    val datastore: ScatterbrainDatastore,
    val connection: MeshtasticConnection,
    val broadcastReceiver: MeshtasticBroadcastReceiverState,
    val sessionBuilder: MeshtasticSessionSubcomponent.Builder,
    val advertiser: Advertiser,
    val database: Datastore,
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) val timeoutScheduler: Scheduler,
) : MeshtasticRadioModule {


    private val log by scatterLog()


    private val currentTransactions = ConcurrentHashMap<String, MeshtasticSessionSubcomponent>()
    private val backlog = ConcurrentLinkedQueue<MeshtasticSessionSubcomponent>()

    override fun popBacklog(): MeshtasticSessionSubcomponent? {
        return backlog.poll()
    }

    override fun startBacklog(from: String) {
        val session = currentTransactions.compute(from) { k, v ->
            when (v) {
                null -> sessionBuilder.id(k).build()
                else -> v
            }
        }!!

        backlog.add(session)
    }

    override fun startSession(from: String): MeshtasticSessionSubcomponent {
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

    override fun stopSession(from: String) {
        currentTransactions.remove(from)
    }

    override fun getSessionCount(): Int {
        return currentTransactions.size
    }



    override fun handlePackets(): Completable {
        return Completable.complete()
    }

    override fun handshake(): Completable {
        log.w("explicit handshake!")
        return Completable.complete()
    }
}