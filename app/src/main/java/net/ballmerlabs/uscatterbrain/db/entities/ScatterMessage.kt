package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import net.ballmerlabs.uscatterbrain.network.Verifiable
import java.util.*

/**
 * helper class representing a relation between a message and its hashes
 */
data class ScatterMessage(
        @Embedded
        var message: HashlessScatterMessage,

        @Relation(parentColumn = "messageID", entityColumn = "hashID")
        var messageHashes: List<Hashes>,

        @Relation(parentColumn = "messageID", entityColumn = "message")
        val recipient_fingerprints: List<IdentityId>,

        @Relation(parentColumn = "messageID", entityColumn = "message")
        val identity_fingerprints: List<IdentityId>

) : Verifiable {
    override val toFingerprint: List<UUID>
    get() = recipient_fingerprints.map { i -> i.uuid }

    override val fromFingerprint: List<UUID>
    get() = identity_fingerprints.map { i -> i.uuid }

    override val application: String
    get() = message.application

    override val extension: String
    get() = message.extension

    override val mime: String
    get() = message.mimeType

    override val toDisk: Boolean
    get() = message.body != null

    override val hashes: Array<ByteArray>
    get() = HashlessScatterMessage.hashes2hash(messageHashes).map { v -> v }.toTypedArray()

    override val signature: ByteArray?
        get() = message.sig

    override val sendDate: Long
        get() = message.sendDate

    override val userFilename: String
        get() = message.userFilename
        }