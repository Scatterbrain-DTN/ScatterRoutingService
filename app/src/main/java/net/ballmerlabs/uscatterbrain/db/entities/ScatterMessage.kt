package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Embedded
import androidx.room.Ignore
import androidx.room.Junction
import androidx.room.Relation
import com.goterl.lazycode.lazysodium.interfaces.Hash
import net.ballmerlabs.uscatterbrain.network.Verifiable

/**
 * helper class representing a relation between a message and its hashes
 */
data class ScatterMessage(
        @Embedded
        var message: HashlessScatterMessage,

        @Relation(parentColumn = "messageID", entityColumn = "hashID", associateBy = Junction(MessageHashCrossRef::class))
        var messageHashes: List<Hashes>
        ) : Verifiable {
    override val toFingerprint: ByteArray?
    get() = message.to

    override val fromFingerprint: ByteArray?
    get() = message.from

    override val application: String
    get() = message.application.decodeToString()

    override val extension: String
    get() = message.extension

    override val mime: String
    get() = message.mimeType

    override val toDisk: Boolean
    get() = message.body != null

    override val hashes: Array<ByteArray>
    get() = HashlessScatterMessage.hashes2hash(messageHashes).map { v -> v.toByteArray() }.toTypedArray()

    override var signature: ByteArray? = null
        get() = message.sig
        set(value) {
            message.sig = value
            field = value
        }

    override val userFilename: String?
        get() = message.userFilename
        }