package net.ballmerlabs.uscatterbrain.network.desktop

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import androidx.lifecycle.AtomicReference
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import net.ballmerlabs.scatterbrainsdk.PairingStage
import net.ballmerlabs.scatterbrainsdk.PairingState
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.scatterbrainsdk.ScatterbrainBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.proto.*


import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.b64
import net.ballmerlabs.uscatterbrain.network.wifidirect.PortSocket
import net.ballmerlabs.uscatterbrain.network.wifidirect.ServerSocketManager
import net.ballmerlabs.uscatterbrain.scheduler.DesktopSession
import net.ballmerlabs.uscatterbrain.util.scatterLog
import proto.Scatterbrain
import java.net.Inet6Address
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named

@DesktopApiScope
class DesktopApiServerImpl @Inject constructor(
    val serverSocketManager: ServerSocketManager,
    val state: DesktopKeyManager,
    val datastore: Datastore,
    val sbDatatore: ScatterbrainDatastore,
    val serverSocket: PortSocket,
    private val finalizer: DesktopFinalizer,
    val advertiser: NsdAdvertiser,
    val context: Context,
    private val sessionState: DesktopApiSessionState,
    private val sessionBuilder: DesktopSessionSubcomponent.Builder,
    private val broadcaster: Broadcaster,
    private val connectivityManager: ConnectivityManager,
    @Named(DesktopApiSubcomponent.NamedSchedulers.API_SERVER_SCHEDULER) val scheduler: Scheduler,
) : DesktopApiServer {

    private val LOG by scatterLog()
    private val serveDisposable = AtomicReference<Disposable?>(null)
    private val ackSubject = PublishSubject.create<Pair<ByteArray, Boolean>>()
    private val sessions = ConcurrentHashMap<String, DesktopSession>()
    private val networkCallback = object : NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            broadcaster.broadcastState(
                addr = DesktopAddrs(linkProperties.linkAddresses.map { v ->
                    DesktopAddr(
                        addr = v.address.address,
                        ipv6 = v.address is Inet6Address,
                        port = serverSocket.socket.localPort
                    )
                }.toImmutableList())
            )
        }
    }
    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun confirm(handle: UUID, identity: UUID): Completable {
        return Completable.fromAction {
            sessionState.importState[handle] = ImportIdentityResponse(
                FinalResult(
                    handle = handle,
                    identity = identity
                )
            )
        }.doOnComplete { broadcaster.broadcastState(null) }
    }


    override fun broadcastIdentities(identities: List<IdentityPacket>): Completable {
        return Completable.fromAction {
            sessionState.eventState.forEach { (k, v) ->
                v.onEvent(DesktopEvent.fromIdentities(
                    identities
                        .filter { v -> !v.isEnd }
                        .map { v ->
                            DesktopApiIdentity(
                                fingerprint = v.uuid!!,
                                isOwned = false,
                                name = v.name,
                                sig = v.getSig(),
                                extraKeys = v.keymap.mapValues { m -> m.value.toByteArray() },
                                publicKey = v.pubkey!!
                            )
                        }
                ))
            }
        }.subscribeOn(scheduler)
    }


    override fun broadcastMessages(messages: List<DbMessage>): Completable {
        return Completable.fromAction {
            LOG.v("broadcastMessages ${messages.size} ${sessionState.eventState.size}")
            sessionState.eventState.forEach { (k, v) ->
                v.onEvent(DesktopEvent.fromDbMessages(messages))
            }
        }
    }


    private fun broadcastPairingState(state: PairingState) {
        LOG.v("broadcastPairingState ${state.appName}")
        context.sendBroadcast(Intent(ScatterbrainApi.PAIRING_EVENT).apply {
            putExtra(ScatterbrainBroadcastReceiver.EXTRA_PAIRING_STATE, state)
        }, ScatterbrainApi.PERMISSION_SUPERUSER)
    }

    private fun handleKeyExchange(
        socket: Socket,
        kp: PublicKeyPair,
    ): Single<DesktopSessionSubcomponent> {
        return ScatterSerializable.parseWrapperFromCRC(
            PairingInitiateParser.parser,
            socket.getInputStream(),
            scheduler
        )
            .flatMap { v ->
                datastore.desktopClientDao().upsertClient(v.pubkey, kp).flatMap { client ->
                    LOG.v("got client from db ${client.paired}")
                    val sessionConfig = kp.session(v.pubkey, client)
                    val stateEntry = sessionState.eventState.compute(v.pubkey.b64()) { k, v ->
                        when(v) {
                            null -> StateEntry()
                            else -> v
                        }
                    }!!
                    val session = sessionBuilder.config(sessionConfig)
                        .socket(socket)
                        .stateEntry(stateEntry)
                        .build()
                    sessions[sessionConfig.fingerprint.b64()] = session.session()
                    PairingAck(
                        pubkey = kp.pubkey,
                        session = client.getHeader(0)
                    ).writeToStream(socket.getOutputStream(), scheduler)
                        .flatMapSingle { s -> s.toSingleDefault(session) }
                }
            }
    }

    private fun handlePairingRequst(
        message: PairingRequest,
        session: DesktopSession,
    ): Completable {
        LOG.w("pairing request from ${message.packet.name}")
        broadcastPairingState(
            PairingState(
                appName = message.packet.name,
                stage = PairingStage.INITIATE,
                identity = session.fingerprint
            )
        )
        return ackSubject.takeUntil { v -> v.first.contentEquals(session.fingerprint) }
            .flatMapSingle { v ->
                LOG.v("updating paired: ${v.second}")
                session.db.paired = v.second
                val c = if(v.second) {
                    sbDatatore.addACLs(message.packet.name, session.fingerprint.b64(), desktop = true)
                } else {
                    Completable.complete()
                }
                c.andThen(datastore.desktopClientDao().updateClient(session.db).toSingleDefault(v))
            }
            .flatMapCompletable { v ->
                session.encrypt(AckPacket.newBuilder(v.second).build())
            }
    }

    override fun serve() {
        val disp = advertiser.startAdvertise()
            .retry(5)
            .andThen(
                serverSocket
                    .accept(scheduler)
                    .repeat()
                    .retry()
                    .subscribeOn(scheduler)
                    .doOnSubscribe { broadcaster.broadcastState(
                        power = DesktopPower.ENABLED
                    ) }
                    .flatMapCompletable { s ->
                        state.getKeypair().flatMapCompletable {kp ->
                            handleKeyExchange(s.socket, kp).flatMapCompletable { session ->
                                session.session().parseTypePrefix(s.socket.getInputStream(), scheduler)
                                    .repeat()
                                    .concatMapCompletable { v ->
                                        LOG.v("got packet type ${v.type}")
                                        when (v.type) {
                                            Scatterbrain.MessageType.PAIRING_REQUEST-> handlePairingRequst(
                                                v.get(),
                                                session.session()
                                            )

                                            else -> session.session().handleMessage(v)
                                        }
                                    }
                            }.doOnError { err ->
                                LOG.e("error in desktop client stream: $err")
                                err.printStackTrace()
                            }
                                .onErrorComplete()
                        }
                    })
            .subscribe(
                { LOG.w("desktop server completed") },
                { err -> LOG.e("desktop server error $err") }
            )

        serveDisposable.getAndSet(disp)?.dispose()
    }

    override fun shutdown() {
        try {
            advertiser.stopAdvertise()
            broadcaster.broadcastState(power = DesktopPower.DISABLED)
            serveDisposable.getAndSet(null)?.dispose()
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (exc: Exception) {
            LOG.w("exception in desktop api server shutdown $exc")
        }
    }

    protected fun finalize() {
        finalizer.onFinalize()
    }

    override fun authorize(fingerprint: ByteArray, authorize: Boolean): Completable {
        return Completable.fromAction {
            val id = LibsodiumInterface.base64encUrl(fingerprint)
            LOG.w("authorize $id $authorize")
            ackSubject.onNext(Pair(fingerprint, authorize))
            broadcastPairingState(
                PairingState(
                    appName = "",
                    stage = PairingStage.ACK,
                    identity = fingerprint
                )
            )
        }
    }
}