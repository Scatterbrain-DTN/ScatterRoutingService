package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.compare
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.BitSet
import java.util.Date
import java.util.UUID

/**
 * Room Dao containing queries and operations on messages stored
 * in the database.
 * TODO: fix some of these being handled manually without room
 */
@Dao
abstract class ScatterMessageDao {

    private val log by scatterLog()

    @get:Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash")
    abstract val all: Single<List<DbMessage>>

    @get:Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash")
    abstract val messagesWithFiles: Single<List<DbMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE messageID IN (:ids)")
    abstract fun getByID(vararg ids: Long): Single<DbMessage>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE uuid = :uuids")
    abstract fun getByUUID(uuids: UUID): Single<DbMessage>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (application = :application) AND (receiveDate BETWEEN :start AND :end) LIMIT :limit")
    abstract fun getByReceiveDate(
        application: String,
        start: Long,
        end: Long,
        limit: Int = -1,
    ): Single<List<DbMessage>>


    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (application = :application) AND (receiveDate BETWEEN :start AND :end) ORDER BY receiveDate LIMIT :limit")
    abstract fun getByReceiveDateChrono(
        application: String,
        start: Long,
        end: Long,
        limit: Int = -1,
    ): Single<List<DbMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (receiveDate BETWEEN :start AND :end) LIMIT :limit")
    abstract fun getByReceiveDate(start: Long, end: Long, limit: Int = -1): Single<List<DbMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (receiveDate BETWEEN :start AND :end) ORDER BY shareCount DESC LIMIT :limit")
    abstract fun getByReceiveDatePriority(
        start: Long,
        end: Long,
        limit: Int,
    ): Single<List<DbMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE packageName = :packageName ORDER BY shareCount DESC LIMIT :limit")
    abstract fun getByReceiveDatePriority(packageName: String, limit: Int): Single<List<DbMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE (application = :application) AND (sendDate BETWEEN :start AND :end) LIMIT :limit")
    abstract fun getBySendDate(
        application: String,
        start: Long,
        end: Long,
        limit: Int = -1,
    ): Single<List<DbMessage>>

    @Transaction
    @Query("DELETE FROM messages WHERE receiveDate BETWEEN :start AND :end")
    abstract fun deleteByDate(start: Long, end: Long): Single<Int>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE filepath IN (:filePaths)")
    abstract fun getByFilePath(vararg filePaths: String): Single<List<DbMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash where application IN (:application)")
    abstract fun getByApplication(application: String): Single<List<DbMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash where application IN (:application) ORDER BY receiveDate LIMIT :limit")
    abstract fun getByApplicationChrono(
        application: String,
        limit: Int = -1,
    ): Single<List<DbMessage>>

    @get:Query("SELECT filepath FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash")
    abstract val allFiles: Single<List<String>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE messageID = (SELECT message FROM identityid WHERE uuid = :ids)")
    abstract fun getByIdentity(ids: UUID): Single<List<DbMessage>>

    @Transaction
    @Query("SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopRandom(count: Int): Single<List<DbMessage>>


    @Query("SELECT SUM(fileSize) FROM messages")
    abstract fun getTotalSize(): Single<Long>

    @Query("UPDATE messages  SET shareCount = shareCount + 1 WHERE fileGlobalHash = :globalhash")
    abstract fun incrementShareCount(globalhash: ByteArray): Single<Int>

    @Transaction
    @Query(
        "SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash WHERE globalhash NOT IN (:globalhashes)" +
                "ORDER BY fileSize ASC, shareCount ASC LIMIT :count"
    )
    abstract fun getTopRandomExcludingHash(
        count: Int,
        globalhashes: List<ByteArray>,
    ): Single<List<DbMessage>>

    @Transaction
    @Query(
        "SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash " +
                "WHERE fileGlobalHash NOT IN (:globalhashes)" +
                "AND fileSize < :sizeLimit ORDER BY fileSize ASC, shareCount ASC LIMIT :count"
    )
    abstract fun getTopRandomExcludingHash(
        count: Int,
        globalhashes: List<ByteArray>,
        sizeLimit: Long,
    ): Single<List<DbMessage>>

    @Transaction
    @Query("SELECT fileGlobalHash FROM messages ORDER BY RANDOM() LIMIT :count")
    abstract fun getTopHashes(count: Int): Single<List<ByteArray>>



    @Query("SELECT * FROM messages")
    abstract fun getAllMessages(): List<HashlessScatterMessage>




    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertMessagesEntity(messages: List<HashlessScatterMessage>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertMessagesEntity(message: HashlessScatterMessage): Single<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertHashesEntity(h: List<Hashes>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertIdentityIdEntity(id: List<IdentityId>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertGlobalHash(hash: GlobalHash): Completable

    @Query("select * from metrics where application = :application")
    abstract fun getMetricsByApplication(application: String): Maybe<Metrics>

    @Query("select * from metrics order by messages desc limit :limit ")
    abstract fun getAllMetrics(limit: Int): Single<List<Metrics>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertMetrics(metrics: Metrics): Completable

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertMetricsIgnore(metrics: Metrics): Completable

//    @Query("SELECT * FROM bundles WHERE  IS NULL LIMIT 1")
//    abstract fun getDefaultMerkleRoot(): Maybe<MerkleBundle>


//    fun insertMerkleBundleUnderRoot(root: MerkleBundle, node: MerkleBundle): Maybe<Long> {
//        return getChildCountForBundle(root.hash).flatMapMaybe { c->
//            if (c < MERKLE_WIDTH) {
//                node.parent = root.hash
//                insertBundleEntity(node).map { v -> v[0] }.toMaybe()
//            } else {
//                getBundlesForBundle(root.hash)
//                    .flatMapMaybe { v ->
//                        Observable.fromIterable(v)
//                            .flatMapMaybe { bundle ->
//                                insertMerkleBundleUnderRoot(bundle, node)
//                            }.firstElement()
//                    }
//            }
//        }
//    }

    @Transaction
    @Insert
    fun updateMetrics(application: String, messages: Long = 0, signed: Long = 0): Maybe<Metrics> {
        return insertMetricsIgnore(
            Metrics(
                application = application,
                messages = messages,
                signed = signed
            )
        ).andThen(getMetricsByApplication(application)).flatMap { m ->
            m.messages += messages
            m.signed += signed
            m.lastSeen = Date().time
            insertMetrics(m).toSingleDefault(m).toMaybe()
        }
    }

    @Transaction
    @Insert
    fun updateMetrics(metrics: Metrics): Maybe<Metrics> {
        return insertMetricsIgnore(metrics)
            .andThen(getMetricsByApplication(metrics.application))
            .flatMap { m ->
                m.messages += metrics.messages
                m.signed += metrics.signed
                m.lastSeen = Date().time
                insertMetrics(m).toSingleDefault(m).toMaybe()
            }
    }

    @Insert
    abstract fun insertFlags(flags: List<MessageFlags>): Completable

    @Transaction
    @Insert
    fun insertMessage(message: DbMessage): Single<HashlessScatterMessage> {
        return insertGlobalHash(message.file.global)
            .andThen(
                insertMessagesEntity(message.message)
                    .flatMap { messageRes ->
                        message.message.messageID = messageRes
                        message.identity_fingerprints.forEach { f ->
                            f.message = messageRes
                        }
                        message.recipient_fingerprints.forEach { f ->
                            f.message = messageRes
                        }

                        message.flags.forEach { f ->
                            f.parentMessage = messageRes
                        }

                        insertIdentityIdEntity(message.identity_fingerprints)
                            .ignoreElement()
                            .andThen(insertFlags(message.flags))
                            .andThen(insertIdentityIdEntity(message.recipient_fingerprints))
                            .ignoreElement()
                            .andThen(insertHashesEntity(message.file.messageHashes))
                            .ignoreElement()
                            .toSingleDefault(message.message)
                    })
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