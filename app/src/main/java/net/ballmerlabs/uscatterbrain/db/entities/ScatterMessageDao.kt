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
    @get:Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash")
    @get:Transaction
    abstract val all: Single<List<ScatterMessage>>

    @get:Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash")
    @get:Transaction
    abstract val messagesWithFiles: Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE messageID IN (:ids)")
    abstract fun getByID(vararg ids: Long): Single<ScatterMessage>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE uuid = :uuids")
    abstract fun getByUUID(uuids: UUID): Single<ScatterMessage>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (application = :application) AND (receiveDate BETWEEN :start AND :end)")
    abstract fun getByReceiveDate(application: String, start: Long, end: Long): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (receiveDate BETWEEN :start AND :end)")
    abstract fun getByReceiveDate(start: Long, end: Long): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (receiveDate BETWEEN :start AND :end) ORDER BY shareCount DESC LIMIT :limit")
    abstract fun getByReceiveDatePriority(start: Long, end: Long, limit: Int): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE packageName = :packageName ORDER BY shareCount DESC LIMIT :limit")
    abstract fun getByReceiveDatePriority(packageName: String, limit: Int): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (application = :application) AND (sendDate BETWEEN :start AND :end)")
    abstract fun getBySendDate(application: String, start: Long, end: Long): Single<List<ScatterMessage>>

    @Transaction
    @Query("DELETE FROM messages WHERE receiveDate BETWEEN :start AND :end")
    abstract fun deleteByDate(start: Long, end: Long): Single<Int>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE filepath IN (:filePaths)")
    abstract fun getByFilePath(vararg filePaths: String): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash where application IN (:application)")
    abstract fun getByApplication(application: String): Single<List<ScatterMessage>>

    @get:Query("SELECT filepath FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash")
    @get:Transaction
    abstract val allFiles: Single<List<String>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE messageID = (SELECT message FROM identityid WHERE uuid = :ids)")
    abstract fun getByIdentity(ids: UUID): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandom(count: Int): Single<List<ScatterMessage>>


    @Query("SELECT SUM(fileSize) FROM messages")
    abstract fun getTotalSize(): Single<Long>

    @Query("UPDATE messages  SET shareCount = shareCount + 1 WHERE fileGlobalHash = :globalhash")
    abstract fun incrementShareCount(globalhash: ByteArray): Single<Int>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE globalhash NOT IN (:globalhashes)" +
            "ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandomExclusingHash(count: Int, globalhashes: List<ByteArray>): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash " +
            "WHERE fileGlobalHash NOT IN (:globalhashes)" +
            "AND fileSize < :sizeLimit ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandomExclusingHash(count: Int, globalhashes: List<ByteArray>, sizeLimit: Long): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT fileGlobalHash FROM messages ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopHashes(count: Int): Single<List<ByteArray>>

    @Insert
    abstract fun __insertMessages(messages: List<HashlessScatterMessage>): Single<List<Long>>

    @Insert
    abstract fun __insertMessages(message: HashlessScatterMessage): Single<Long>

    @Insert
    abstract fun __insertHashes(h: List<Hashes>): Single<List<Long>>

    @Insert
    abstract fun __insertIdentityId(id: List<IdentityId>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertGlobalHash(hash: GlobalHash): Completable

    @Transaction
    @Insert
    fun insertMessage(message: ScatterMessage): Completable {

       return insertGlobalHash(message.file.global)
           .andThen(
               __insertMessages(message.message)
                   .flatMapCompletable { messageRes ->
                       message.identity_fingerprints.forEach { f -> f.message = messageRes }
                       message.recipient_fingerprints.forEach { f -> f.message = messageRes }
                       __insertIdentityId(message.identity_fingerprints)
                           .ignoreElement()
                           .andThen(__insertIdentityId(message.recipient_fingerprints))
                           .ignoreElement()
                           .andThen(__insertHashes(message.file.messageHashes))
                           .ignoreElement()
                   }
           )
    }

    @Delete
    abstract fun delete(message: HashlessScatterMessage): Completable


    @Delete
    abstract fun delete(message: GlobalHash): Completable


    @Query("DELETE FROM globalhash WHERE filepath = :path")
    abstract fun deleteByPath(path: String): Int

    @Query("SELECT COUNT(*) FROM messages")
    abstract fun messageCount(): Int

    @Query("SELECT COUNT(*) FROM globalhash WHERE filepath = :path")
    abstract fun messageCount(path: String): Int

    @Query("SELECT COUNT(*) FROM globalhash WHERE filepath = :path")
    abstract fun messageCountSingle(path: String): Single<Int>
}