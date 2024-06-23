package net.ballmerlabs.uscatterbrain.scheduler

import android.content.Context
import com.google.protobuf.MessageLite
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.IdentityResponse
import net.ballmerlabs.uscatterbrain.RoutingServiceBackend
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.b64
import net.ballmerlabs.uscatterbrain.network.desktop.Broadcaster
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiSessionState
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiSubcomponent
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopSessionScope
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopSessionSubcomponent
import net.ballmerlabs.uscatterbrain.network.desktop.IdentityImportState
import net.ballmerlabs.uscatterbrain.network.desktop.PublicKeyPair
import net.ballmerlabs.uscatterbrain.network.desktop.StateEntry
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
import net.ballmerlabs.uscatterbrain.network.desktop.getAll
import net.ballmerlabs.uscatterbrain.network.proto.CryptoMessage
import net.ballmerlabs.uscatterbrain.network.proto.CryptoMessageParser
import net.ballmerlabs.uscatterbrain.network.proto.EventsResponse
import net.ballmerlabs.uscatterbrain.network.proto.GenerateIdentityCommand
import net.ballmerlabs.uscatterbrain.network.proto.GenerateIdentityResponse
import net.ballmerlabs.uscatterbrain.network.proto.GetEventsCommand
import net.ballmerlabs.uscatterbrain.network.proto.GetIdentityCommand
import net.ballmerlabs.uscatterbrain.network.proto.GetMessageCommand
import net.ballmerlabs.uscatterbrain.network.proto.ImportIdentityCommand
import net.ballmerlabs.uscatterbrain.network.proto.ImportIdentityResponse
import net.ballmerlabs.uscatterbrain.network.proto.MessageResponse
import net.ballmerlabs.uscatterbrain.network.proto.SendMessageCommand
import net.ballmerlabs.uscatterbrain.network.proto.UnitResponse
import net.ballmerlabs.uscatterbrain.util.scatterLog
import proto.Scatterbrain
import proto.Scatterbrain.RespCode
import java.io.InputStream
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named

@DesktopSessionScope
class DesktopSession @Inject constructor(
    @Named(DesktopSessionSubcomponent.NamedKeys.TX) val tx: ByteArray,
    @Named(DesktopSessionSubcomponent.NamedKeys.RX) val rx: ByteArray,
    @Named(DesktopSessionSubcomponent.NamedKeys.FINGERPRINT) val fingerprint: ByteArray,
    @Named(DesktopSessionSubcomponent.NamedKeys.REMOTEPUB) val remotepub: ByteArray,
    val db: DesktopClient,
    val kx: PublicKeyPair,
    val context: Context,
    val state: DesktopApiSessionState,
    @Named(DesktopApiSubcomponent.NamedSchedulers.API_SERVER_SCHEDULER) val scheduler: Scheduler,
    val backend: RoutingServiceBackend,
    val scatterbrainDatastore: ScatterbrainDatastore,
    val stateEntry: StateEntry,
    val broadcaster: Broadcaster,
    val socket: Socket,
) {
    private val LOG by scatterLog()

    fun parseTypePrefix(
        inputStream: InputStream, scheduler: Scheduler,
    ): Single<ScatterSerializable.Companion.TypedPacket> {
        return ScatterSerializable.parseWrapperFromCRC(
            CryptoMessageParser.parser,
            inputStream,
            scheduler
        ).map { cm ->
            cm.decryptTypePrefix(tx)
        }
    }

    private fun getIdentity(
        packet: GetIdentityCommand,
    ): Completable {
        return backend.datastore.getDesktopIdentitiesByFingerprint(packet.id)
            .flatMapCompletable { id ->
                val resp = IdentityResponse(
                    header = db.getHeader(0),
                    identities = id,
                    respcode = RespCode.OK
                )
                encrypt(resp)
            }.onErrorResumeNext {
                encrypt(
                    IdentityResponse(
                        header = db.getHeader(0),
                        identities = listOf(),
                        respcode = RespCode.ERR
                    )
                )
            }
    }

    private fun success(): Completable {
        return encrypt(
            UnitResponse(
                success = RespCode.OK
            )
        )
    }

    private fun fail(message: String): Completable {
        return encrypt(
            UnitResponse(
                success = RespCode.ERR,
                message = message
            )
        )
    }

    private fun sendMessages(
        packet: SendMessageCommand,
    ): Completable {
        LOG.v("sendMessages ${packet.data.size}")
        return backend.datastore.insertMessageFromDesktop(
            packet.data,
            fingerprint.b64(),
            packet.identity
        ).onErrorResumeNext { err -> fail("Failed to send messages: $err") }
            .andThen(success())
    }


    private fun getMessages(
        packet: GetMessageCommand,
    ): Completable {
        val application = packet.application
        return if (application == null) {
            fail("null application currently not supported")
        } else if (packet.sendDate != null) {
            scatterbrainDatastore.getApiMessagesSendDate(
                application,
                packet.sendDate!!.fromval,
                packet.sendDate!!.toval,
                packet.limit
            ).flatMapCompletable { v ->
                encrypt(
                    MessageResponse(
                        header = db.getHeader(0),
                        messages = v,
                        respcode = RespCode.OK
                    )
                )
            }
        } else if (packet.receiveDate != null) {
            scatterbrainDatastore.getApiMessagesReceiveDate(
                application,
                packet.receiveDate!!.fromval,
                packet.receiveDate!!.toval,
                packet.limit
            ).flatMapCompletable { v ->
                encrypt(
                    MessageResponse(
                        header = db.getHeader(0),
                        messages = v,
                        respcode = RespCode.OK
                    )
                )
            }
        } else {
            scatterbrainDatastore.getApiMessages(
                application,
                packet.limit
            ).flatMapCompletable { v ->
                encrypt(
                    MessageResponse(
                        header = db.getHeader(0),
                        messages = v,
                        respcode = RespCode.OK
                    )
                )
            }
        }
    }


    private fun generateIdentity(
        packet: GenerateIdentityCommand,
    ): Completable {
        return backend.generateIdentity(
            packet.name,
            fingerprint.b64(),
            true
        ).flatMapCompletable { id ->
            encrypt(GenerateIdentityResponse(id.fingerprint))
        }.onErrorResumeNext { encrypt(GenerateIdentityResponse(RespCode.ERR)) }
    }

    private fun importIdentity(
        packet: ImportIdentityCommand,
    ): Completable {
        val handle = packet.handle ?: UUID.randomUUID()
        return Completable.defer {
            val n = state.importState.compute(handle) { k, v ->
                v ?: ImportIdentityResponse(
                        k
                    )
            }!!

            if (n.name == null)
                broadcaster.broadcastState(
                    state = IdentityImportState(
                        appSig = fingerprint,
                        appName = db.name,
                        handle = handle
                    )
                )

            encrypt(n)
        }
    }

    private fun getEvents(
        packet: GetEventsCommand,
    ): Completable {
        return Completable.defer {
            val head = stateEntry.queue.getAll(packet.count)
            LOG.v("getEvents ${head.size}")
            if (packet.block) {
                if (head.isNotEmpty()) {
                    encrypt(EventsResponse(head))
                } else {
                    stateEntry.events.firstOrError().flatMapCompletable {
                        val h2 = stateEntry.queue.getAll(packet.count)
                        encrypt(EventsResponse(h2))
                    }
                }
            } else {
                encrypt(EventsResponse(head))
            }
        }
    }

    fun handleMessage(
        packet: ScatterSerializable.Companion.TypedPacket,
    ): Completable {
        return Completable.defer {
            if (isPaired()) {
                when (packet.type) {
                    Scatterbrain.MessageType.GET_IDENTITY -> getIdentity(packet.get())
                    Scatterbrain.MessageType.GET_MESSAGE -> getMessages(packet.get())
                    Scatterbrain.MessageType.SEND_MESSAGE -> sendMessages(packet.get())
                    Scatterbrain.MessageType.IMPORT_IDENTITY -> importIdentity(packet.get())
                    Scatterbrain.MessageType.GENERATE_IDENTITY -> generateIdentity(packet.get())
                    Scatterbrain.MessageType.GET_EVENTS -> getEvents(packet.get())
                    else -> fail("Invalid command")
                }
            } else {
                LOG.w("session ${db.session} not paired, disconnecting")
                success()
            }
        }
    }


    private fun isPaired(): Boolean {
        LOG.v("isPaired ${db.paired}")
        return db.paired && remotepub.contentEquals(db.remotekey)
    }


    fun <T : MessageLite, U : ScatterSerializable<T>> encrypt(message: U): Completable {
        return Completable.defer {
            CryptoMessage.fromMessage(rx, message)
                .writeToStream(socket.getOutputStream(), scheduler)
                .flatMapCompletable { v -> v }
        }
    }

    inline fun <reified T : ScatterSerializable<V>, reified V : MessageLite> parseWrapperFromCRC(
        parser: ScatterSerializable.Companion.Parser<V, T>,
        inputStream: InputStream,
        scheduler: Scheduler,
    ): Single<T> {
        return ScatterSerializable.parseWrapperFromCRC(
            CryptoMessageParser.parser,
            inputStream,
            scheduler
        ).map { cm ->
            val v = cm.decrypt(parser, tx)
            T::class.java.getConstructor(V::class.java).newInstance(v)
        }
    }

}