package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.*
import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.db.getGlobalHashDb
import java.util.*

/**
 * Room Dao containing queries and operations on messages stored
 * in the database.
 * TODO: fix some of these being handled manually without room
 */
@Dao
abstract class ScatterMessageDao {
    @get:Query("SELECT * FROM messages")
    @get:Transaction
    abstract val all: Single<List<ScatterMessage>>

    @get:Query("SELECT * FROM messages")
    @get:Transaction
    abstract val messagesWithFiles: Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    abstract fun getByID(vararg ids: Long): Single<ScatterMessage>

    @Transaction
    @Query("SELECT * FROM messages WHERE uuid = :uuids")
    abstract fun getByUUID(uuids: UUID): Single<ScatterMessage>

    @Transaction
    @Query("SELECT * FROM messages WHERE (application = :application) AND (receiveDate BETWEEN :start AND :end)")
    abstract fun getByReceiveDate(application: String, start: Long, end: Long): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages WHERE (receiveDate BETWEEN :start AND :end)")
    abstract fun getByReceiveDate(start: Long, end: Long): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages WHERE (receiveDate BETWEEN :start AND :end) ORDER BY shareCount DESC LIMIT :limit")
    abstract fun getByReceiveDatePriority(start: Long, end: Long, limit: Int): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages WHERE packageName = :packageName ORDER BY shareCount DESC LIMIT :limit")
    abstract fun getByReceiveDatePriority(packageName: String, limit: Int): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages WHERE (application = :application) AND (sendDate BETWEEN :start AND :end)")
    abstract fun getBySendDate(application: String, start: Long, end: Long): Single<List<ScatterMessage>>

    @Transaction
    @Query("DELETE FROM messages WHERE receiveDate BETWEEN :start AND :end")
    abstract fun deleteByDate(start: Long, end: Long): Single<Int>

    @Transaction
    @Query("SELECT * FROM messages WHERE filePath IN (:filePaths)")
    abstract fun getByFilePath(vararg filePaths: String): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages where application IN (:application)")
    abstract fun getByApplication(application: String): Single<List<ScatterMessage>>

    @get:Query("SELECT filePath FROM messages")
    @get:Transaction
    abstract val allFiles: Single<List<String>>

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID = (SELECT message FROM identityid WHERE uuid = :ids)")
    abstract fun getByIdentity(ids: UUID): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandom(count: Int): Single<List<ScatterMessage>>


    @Query("SELECT SUM(fileSize) FROM messages")
    abstract fun getTotalSize(): Single<Long>

    @Query("UPDATE messages SET shareCount = shareCount + 1 WHERE globalhash = :globalhash")
    abstract fun incrementShareCount(globalhash: ByteArray): Single<Int>

    @Transaction
    @Query("SELECT * FROM messages WHERE globalhash NOT IN (:globalhashes)" +
            "ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandomExclusingHash(count: Int, globalhashes: List<ByteArray>): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages WHERE globalhash NOT IN (:globalhashes)" +
            "AND fileSize < :sizeLimit ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandomExclusingHash(count: Int, globalhashes: List<ByteArray>, sizeLimit: Long): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT globalhash FROM messages ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopHashes(count: Int): Single<List<ByteArray>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun __insertMessagesWithHashes(messagesWithHashes: Array<MessageHashCrossRef?>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun __insertMessages(messages: List<HashlessScatterMessage>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun __insertMessages(message: HashlessScatterMessage): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun __insertHashes(h: List<Hashes>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun __insertIdentityId(id: List<IdentityId>): List<Long>


    @Transaction
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMessage(message: ScatterMessage) {
        val res = __insertHashes(message.messageHashes)
        val messageRes = __insertMessages(message.message)
        message.message.globalhash = getGlobalHashDb(message.messageHashes)
        val hashXrefList = arrayOfNulls<MessageHashCrossRef>(res.size)
        for (i in res.indices) {
            val xref = MessageHashCrossRef(
                    messageRes,
                    res[i]
            )

            hashXrefList[i] = xref
        }
        __insertMessagesWithHashes(hashXrefList)
        message.identity_fingerprints.forEach { f -> f.message = messageRes }
        message.recipient_fingerprints.forEach { f -> f.message = messageRes }

        __insertIdentityId(message.identity_fingerprints)
        __insertIdentityId(message.recipient_fingerprints)

    }

    @Delete
    abstract fun delete(message: HashlessScatterMessage): Completable

    @Query("DELETE FROM messages WHERE filepath = :path")
    abstract fun deleteByPath(path: String): Int

    @Query("SELECT COUNT(*) FROM messages")
    abstract fun messageCount(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE filepath = :path")
    abstract fun messageCount(path: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE filepath = :path")
    abstract fun messageCountSingle(path: String): Single<Int>
}