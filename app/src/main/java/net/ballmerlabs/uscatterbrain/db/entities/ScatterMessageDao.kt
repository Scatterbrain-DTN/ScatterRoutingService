package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.*
import io.reactivex.Completable
import io.reactivex.Single

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
    @Query("SELECT * FROM messages WHERE filePath IN (:filePaths)")
    abstract fun getByFilePath(vararg filePaths: String): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages where application IN (:application)")
    abstract fun getByApplication(application: String): Single<List<ScatterMessage>>

    @get:Query("SELECT filePath FROM messages")
    @get:Transaction
    abstract val allFiles: Single<List<String>>

    @Transaction
    @Query("SELECT * FROM messages WHERE identity_fingerprint IN (:ids)")
    abstract fun getByIdentity(ids: String): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandom(count: Int): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT * FROM messages WHERE globalhash NOT IN (:globalhashes)" +
            "ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandomExclusingHash(count: Int, globalhashes: List<ByteArray>): Single<List<ScatterMessage>>

    @Transaction
    @Query("SELECT globalhash FROM messages ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopHashes(count: Int): Single<List<ByteArray>>

    @Transaction
    @Insert
    abstract fun insertMessagesWithHashes(messagesWithHashes: List<MessageHashCrossRef>): Single<List<Long>>

    @Insert
    abstract fun _insertMessages(messages: List<HashlessScatterMessage>): Single<List<Long>>

    @Insert
    abstract fun _insertMessages(message: HashlessScatterMessage): Single<Long>

    @Insert
    abstract fun insertHashes(h: List<Hashes>): Single<List<Long>>

    @Insert
    abstract fun insertIdentity(identity: KeylessIdentity): Single<Long>

    @Insert
    abstract fun insertIdentities(ids: List<KeylessIdentity>): Single<List<Long>>

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