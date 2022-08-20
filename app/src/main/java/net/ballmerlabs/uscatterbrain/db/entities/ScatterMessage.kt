package net.ballmerlabs.uscatterbrain.db.entities

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.room.Embedded
import androidx.room.Relation
import net.ballmerlabs.uscatterbrain.db.getGlobalHash
import net.ballmerlabs.uscatterbrain.db.hashAsUUID
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.Verifiable
import java.io.File
import java.util.*

/**
 * helper class representing a relation between a message and its hashes
 */
data class ScatterMessage(
    @Embedded
    var message: HashlessScatterMessage,

    @Embedded
    val file: DiskFile,

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

    override val isFile: Boolean
        get() = message.body != null

    override val hashes: Array<ByteArray>
        get() = HashlessScatterMessage.hashes2hash(file.messageHashes).map { v -> v }.toTypedArray()

    override val signature: ByteArray?
        get() = message.sig

    override val sendDate: Long
        get() = message.sendDate

    override val userFilename: String
        get() = message.userFilename

    companion object {


        fun from(
            headerPacket: BlockHeaderPacket,
            prefix: File,
            packageName: String = ""
        ): ScatterMessage? {
            return if (headerPacket.isEndOfStream)
                null
            else {
                val globalhash = getGlobalHash(headerPacket.hashList)
                val hm = HashlessScatterMessage(
                    body = null,
                    application = headerPacket.application,
                    sig = headerPacket.signature,
                    sessionid = headerPacket.sessionID,
                    extension = headerPacket.extension,
                    userFilename = headerPacket.userFilename,
                    mimeType = headerPacket.mime,
                    sendDate = headerPacket.sendDate,
                    receiveDate = Date().time,
                    fileSize = -1,
                    packageName = packageName,
                    fileGlobalHash = globalhash
                )

                ScatterMessage(
                    hm,
                    DiskFile(
                        messageHashes = HashlessScatterMessage.hash2hashs(
                            headerPacket.hashList,
                            globalhash
                        ),
                        global = GlobalHash(
                            globalhash = globalhash,
                            filePath = prefix.absolutePath + hashAsUUID(globalhash).toString()+ headerPacket.extension
                        )
                    ),
                    headerPacket.toFingerprint.map { v -> IdentityId(v) },
                    headerPacket.fromFingerprint.map { v -> IdentityId(v) }
                )
            }
        }

        fun getPath(
            prefix: File,
            message: net.ballmerlabs.scatterbrainsdk.ScatterMessage,
            hashes: List<ByteArray>
        ): File {
            return getPath(prefix, message, getGlobalHash(hashes))
        }
        fun getPath(
            prefix: File,
            message: net.ballmerlabs.scatterbrainsdk.ScatterMessage,
            globalHash: ByteArray
        ): File {
            return  File(
                prefix, hashAsUUID(globalHash).toString() + message.extension
            )
        }


        fun from(
            message: net.ballmerlabs.scatterbrainsdk.ScatterMessage,
            hashes: List<ByteArray>,
            prefix: File,
            packageName: String = "",
            bytes: ByteArray? = null
        ): ScatterMessage {

            val globalhash = getGlobalHash(hashes)
            val newFile = getPath(prefix, message, globalhash)
            val hm = HashlessScatterMessage(
                body = bytes,
                application = message.application,
                sig = null,
                sessionid = 0,
                extension = message.extension,
                fileGlobalHash = globalhash,
                mimeType = MimeTypeMap.getFileExtensionFromUrl(
                    Uri.fromFile(
                        newFile
                    ).toString()
                ),
                sendDate = Date().time,
                receiveDate = Date().time,
                fileSize = newFile.length(),
                packageName = packageName
            )
            val dbmessage = ScatterMessage(
                hm,
                DiskFile(
                    messageHashes = HashlessScatterMessage.hash2hashs(hashes, globalhash),
                    global = GlobalHash(
                        globalhash = globalhash,
                        filePath = newFile.absolutePath //TODO: this needs to change to cacheDir
                    )
                ),
                if (message.toFingerprint == null)
                    arrayListOf()
                else
                    arrayListOf(IdentityId(message.toFingerprint!!)),
                if (message.fromFingerprint == null)
                    arrayListOf()
                else
                    arrayListOf(IdentityId(message.fromFingerprint!!))
            )
            dbmessage.message = hm
            return dbmessage
        }
    }
}