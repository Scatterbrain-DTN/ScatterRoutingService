package net.ballmerlabs.uscatterbrain.network.desktop

import io.reactivex.Completable
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.network.proto.IdentityPacket
import java.util.UUID
const val ACTION_DESKTOP_EVENT = "net.ballmerlabs.uscatterbrain.ACTION_DESKTOP_EVENT"
const val EXTRA_IDENTITY_IMPORT_STATE = "identity_import_state"
const val EXTRA_DESKTOP_POWER = "desktop_power"
const val EXTRA_DESKTOP_IP = "desktop_ip"
const val EXTRA_APPS = "desktop_apps"

interface DesktopApiServer {
    fun serve()
    fun shutdown()
    fun confirm(handle: UUID, identity: UUID): Completable
    fun broadcastIdentities(identities: List<IdentityPacket>): Completable
    fun broadcastMessages(messages: List<DbMessage>): Completable
    fun authorize(fingerprint: ByteArray, authorize: Boolean): Completable
}