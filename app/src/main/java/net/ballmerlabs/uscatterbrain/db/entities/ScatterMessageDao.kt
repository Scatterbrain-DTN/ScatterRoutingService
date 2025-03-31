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

    @Query("SELECT * FROM messages WHERE bundle = :id ORDER BY fileGlobalHash ASC")
    abstract fun getMessagesForBundle(id: Long): List<HashlessScatterMessage>

//    @Transaction
//    @Query("""
//        SELECT (
//            (SELECT COUNT(*) FROM messages INNER JOIN bundles ON bundles.id = bundle where bundles.hash = :hash)
//            +
//            (SELECT COUNT(*) FROM bundles WHERE parent = :hash)
//        )
//    """)
//    abstract fun getChildCountForBundle(hash: ByteArray): Single<Long>

//    @Transaction
//    @Query(
//        "SELECT * FROM bundles WHERE parent = :parent AND " +
//                "(SELECT COUNT(*) FROM messages WHERE bundle = bundles.id ) < :limit LIMIT 1"
//    )
//    abstract fun getChildBundleUnderLimit(parent: ByteArray, limit: Long): Maybe<MerkleBundle>

    @Query(
        """
        WITH RECURSIVE
            child(hash, level) AS (
                SELECT hash, 0 FROM bundles WHERE hash = :parent
                UNION ALL
                SELECT bundles.hash, child.level + 1 FROM bundles 
                JOIN child ON bundles.childOne = child.hash
                ORDER BY 2
            )
        SELECT * FROM bundles WHERE hash = (SELECT hash FROM child)
    """
    )
    abstract fun getMerkleBundleUnderLimitRecursive(
        parent: ByteArray,
    ): Single<MerkleBundle>

    @Query(
        """
        UPDATE bundles SET childOne = CASE WHEN :childHash >> :pos & 1 == 1 
            THEN :child
            ELSE childOne
        END, childTwo = CASE WHEN :childHash << :pos | 1 == 0 
            THEN :child
            ELSE childTwo
        END WHERE id = :self
        """
    )
    abstract fun updateParentFromHash(
        childHash: ByteArray,
        pos: Int,
        child: Long,
        self: Long,
    ): Completable

    @Query("UPDATE bundles SET hash = :hash WHERE id = :self")
    abstract fun setBundleHash(hash: ByteArray, self: Long): Completable

    @Query(
        """
        WITH RECURSIVE
            parent(id) AS (
                select id from bundles where id = :id
                UNION ALL
                SELECT childOne FROM bundles, parent WHERE bundles.id = parent.id 
                UNION ALL
                SELECT childTwo FROM bundles, parent WHERE bundles.id = parent.id
        )
        SELECT * FROM messages where bundle IN parent
    """
    )
    abstract fun getMessagesForBundleRecursive(id: Long): Single<List<HashlessScatterMessage>>


    @Query(
        """
        WITH RECURSIVE
            parent(pid, pos, cond) AS (
                SELECT bundles.id, :pos + 1, (
                    SELECT (INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, 0/8 + 1, 1)), -2, 1)) * 16 
        + INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, 0/8 + 1, 1)), -1, 1)) >> (0 % 8)) & 1 == 0) AS cond  
                        FROM bundles WHERE id = :root
                UNION ALL
                SELECT 
                CASE WHEN cond THEN childOne ELSE childTwo END, 
                pos + 1, 
                (SELECT 
                    (INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, pos/8 + 1, 1)), -2, 1)) * 16 
        + INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, pos/8 + 1, 1)), -1, 1)) >> (pos % 8)) & 1 == 0
                ) AS cond 
                FROM bundles, parent 
                WHERE bundles.id=parent.pid
                AND
                CASE WHEN cond THEN childOne ELSE childTwo END IS NOT NULL
             ) SELECT pid AS parent, cond AS childOne, NOT cond AS childTwo, pos AS pos FROM parent
             ORDER BY pos DESC LIMIT 1
    """
    )
    abstract fun getInsertionPoint(hash: ByteArray, root: Long, pos: Long): Maybe<MerkleInsertCond>


    @Query(
        """
        WITH RECURSIVE
            parent(pid, pos, cond) AS (
                SELECT bundles.id, :pos + 1, (
                    SELECT (INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, 0/8 + 1, 1)), -2, 1)) * 16 
        + INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, 0/8 + 1, 1)), -1, 1)) >> (0 % 8)) & 1 == 0) AS cond  
                        FROM bundles WHERE id = :root
                UNION ALL
                SELECT 
                CASE WHEN cond THEN childOne ELSE childTwo END, 
                pos + 1, 
                (SELECT 
                    (INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, pos/8 + 1, 1)), -2, 1)) * 16 
        + INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, pos/8 + 1, 1)), -1, 1)) >> (pos % 8)) & 1 == 0
                ) AS cond 
                FROM bundles, parent 
                WHERE bundles.id=parent.pid
                AND
                CASE WHEN cond THEN childOne ELSE childTwo END IS NOT NULL
             ) SELECT pid AS parent, cond AS childOne, NOT cond AS childTwo, pos AS pos FROM parent
             ORDER BY pos DESC
    """
    )
    abstract fun getInsertionPoints(
        hash: ByteArray,
        root: Long,
        pos: Long,
    ): Single<List<MerkleInsertCond>>

    @Query(
        """
        SELECT * FROM bundles AS disjoint 
        WHERE disjoint.id NOT IN (
            SELECT bundles.id FROM bundles 
            INNER JOIN bundles AS parent ON parent.childOne = bundles.id OR parent.childTwo = bundles.id 
        )
    """
    )
    abstract fun getRoots(): Single<List<MerkleBundle>>

    fun getDefaultRoot(): Single<MerkleBundle> {
        return getRootsRandom().flatMapObservable { v -> Observable.fromIterable(v) }
            .firstElement()
            .switchIfEmpty(Single.defer {
                val bundle = MerkleBundle(
                    hash = null,
                    dirty = true
                )
                insertBundleEntity(bundle).map { id ->
                    bundle.apply {
                        this.id = id
                    }
                }
            })
    }

    @Query(
        """
        SELECT * FROM bundles AS disjoint 
        WHERE disjoint.id NOT IN (
            SELECT bundles.id FROM bundles 
            INNER JOIN bundles AS parent ON parent.childOne = bundles.id OR parent.childTwo = bundles.id 
        ) ORDER BY RANDOM()
    """
    )
    abstract fun getRootsRandom(): Single<List<MerkleBundle>>

    @Insert
    abstract fun insertBundleEntity(bundles: List<MerkleBundle>): Single<List<Long>>

    @Query(
        """
        UPDATE bundles SET
            childOne = :child
            WHERE id = :parent
    """
    )
    abstract fun updateParentChildOne(
        parent: Long,
        child: Long,
    ): Completable

    @Query(
        """
        UPDATE bundles SET
            childTwo = :child
            WHERE id = :parent
    """
    )
    abstract fun updateParentChildTwo(
        parent: Long,
        child: Long,
    ): Completable


    @Query("UPDATE messages SET bundle = :bundle WHERE messageID = :messageID")
    abstract fun updateBundleForMessage(bundle: Long, messageID: Long): Completable

    fun getInsertionPointWithoutDb(
        hash: ByteArray,
        id: Long,
        pos: Long,
    ): MerkleInsertCond? {
        val childOne = isChildOne(hash, pos) ?: return null
        return MerkleInsertCond(
            parent = id,
            childOne = childOne,
            childTwo = !childOne,
            pos = pos + 1
        )
    }

    @Query(
        """
        WITH RECURSIVE
       parent(ids, pos) AS (
            SELECT id, 0 FROM bundles WHERE id = :root
            UNION ALL
            SELECT childOne, pos + 1 FROM bundles, parent WHERE parent.ids = bundles.id
            UNION ALL
            SELECT childTwo, pos + 1 FROM bundles, parent WHERE parent.ids = bundles.id
       ) SELECT ids FROM parent INNER JOIN bundles ON bundles.id = ids
        WHERE pos = (SELECT pos FROM parent ORDER BY pos ASC LIMIT 1) AND hash IS NULL AND dirty = 'f'
    """
    )
    abstract fun getDirtyNodes(root: Long): Single<List<Long>>


    @Query(
        """
        WITH RECURSIVE
       parent(ids, pos) AS (
            SELECT id, 0 FROM bundles WHERE id = :id
            UNION ALL
            SELECT id, pos + 1 FROM bundles, parent WHERE parent.ids = bundles.childTwo OR parent.ids = bundles.childOne
       ) SELECT ids FROM parent ORDER BY pos ASC LIMIT :limit
    """
    )
    abstract fun getAncestors(id: Long, limit: Int = 500): Single<List<Long>>

    @Query("SELECT childOne from bundles WHERE id = :id")
    abstract fun getChildOne(id: Long): Long?

    @Query("SELECT childTwo FROM bundles WHERE id = :id")
    abstract fun getChildTwo(id: Long): Long?

    @Query("SELECT * FROM bundles WHERE dirty = 't'")
    abstract fun getDirty(): Single<List<MerkleBundle>>

    @Query("UPDATE bundles SET hash = :hash, dirty = 'f' WHERE id = :id")
    abstract fun updateBundleHash(hash: ByteArray, id: Long)

    @Query("SELECT * FROM bundles")
    abstract fun getAllBundles(): List<MerkleBundle>


//    private fun rehash(dirty: Long): Completable {
//        return getBundlesForBundle(dirty)
//            .flatMapObservable { v -> Observable.fromIterable(v) }
//            .map { v -> v.hash!! }
//            .mergeWith(
//                getMessagesForBundle(dirty)
//                    .flatMapObservable { v -> Observable.fromIterable(v) }
//                    .map { v -> v.fileGlobalHash })
//            .toList()
//            .flatMapCompletable { hashes ->
//                val hash = LibsodiumInterface.merkleHash(hashes)
//                updateBundleHash(hash, dirty)
//            }.andThen(getAncestors(dirty))
//            .flatMapObservable { ancestors -> Observable.fromIterable(ancestors) }
//


    @OptIn(ExperimentalStdlibApi::class)
    private fun merkleRehash(root: Long?, pos: Long = 0) {
        if (root == null) {
            return
        }

        val child1 = getChildOne(root)
        val child2 = getChildTwo(root)
        merkleRehash(child1, pos = pos + 1)
        merkleRehash(child2, pos = pos + 1)

        val messages = getMessagesForBundle(root)
        val bundles = getBundlesForBundle(root)
        val mhash = messages.map { v -> v.fileGlobalHash }
        val bhash = bundles.map { v -> v.hash!! }
        log.v("merkleRehash depth=$pos root=$root")
        log.v("\tmhash=${mhash.map { v -> v.toHexString() }}")
        log.v("\tbhash=${bhash.map { v -> v.toHexString() }}")
        val q = bhash + mhash
        val b = q.sortedWith { v, n -> v.compare(n) }
        log.v("\tcombined=${b.map { v -> v.toHexString() }}")
        val hash = LibsodiumInterface.merkleHash(b)
        log.v("\tfinal=${hash.toHexString()}")
        updateBundleHash(hash, root)
    }


    @OptIn(ExperimentalStdlibApi::class)
    fun merkleRehash(): Completable {
        return getDefaultRoot().flatMapCompletable { r ->
            log.v("merkleRehash start $r")
            Completable.fromAction {
                merkleRehash(r.id)
            }
        }
    }


//    private fun rehash(): Completable {
//        return getDefaultRoot().flatMapCompletable { root ->
//            getDirtyNodes(root.id!!)
//                .flatMapCompletable { dirty ->
//                    rehash(dirty)
//                }
//        }
//    }

    private fun iterativeMerkleInsert(
        message: HashlessScatterMessage,
        point: MerkleInsertCond,
        bundles: ArrayList<MerkleBundle>,
    ): Completable {
        return if (point.complete(message.fileGlobalHash)) {
            message.bundle = point.parent
            log.v("updateParent pos=${point.pos} parent=${point.parent}")
            updateBundleForMessage(point.parent, message.messageID!!)

        } else {
            val bundle = bundles.removeLastOrNull()!!
            val isp = getInsertionPointWithoutDb(
                message.fileGlobalHash,
                bundle.id!!,
                point.pos
            )!!
            val c = if (point.childOne)
                "childOne=${bundle.id}"
            else if (point.childTwo)
                "childTwo=${bundle.id}"
            else
                "DIRTY"
            log.v("updateParent pos=${point.pos} parent=${point.parent} $c")
            if (point.childOne) {
                updateParentChildOne(point.parent, bundle.id!!)
                    .andThen(iterativeMerkleInsert(message, isp, bundles))
            } else if (point.childTwo) {
                updateParentChildTwo(point.parent, bundle.id!!)
                    .andThen(iterativeMerkleInsert(message, isp, bundles))
            } else
                Completable.complete()

        }
    }

    @Transaction
    @Query("""
        SELECT (INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, :pos/8 + 1, 1)), -2, 1)) * 16 
        + INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, :pos/8 + 1, 1)), -1, 1)) >> (:pos % 8)) & 1 == 0
        """)
    abstract fun testBitmask(hash: ByteArray, pos: Long): Boolean

    @Query("SELECT * FROM messages")
    abstract fun getAllMessages(): List<HashlessScatterMessage>


    @Transaction
    @Query("SELECT SUBSTRING(HEX(SUBSTRING(:hash, :pos/8 + 1, 1)), -2, 1)")
    abstract fun testBitmaskHex(hash: ByteArray, pos: Int): String


    fun isChildOne(hash: ByteArray, pos: Long): Boolean? {
        return if (hash.size * Byte.SIZE_BITS > pos) {
            hash[(pos / 8).toInt()].toLong() shr (pos.toInt() % Byte.SIZE_BITS) and 1.toLong() == 0.toLong()
        } else {
            null
        }
    }


    @OptIn(ExperimentalStdlibApi::class)
    private fun insertMerkle(message: HashlessScatterMessage): Completable {
        return getDefaultRoot()
            .flatMapMaybe { root ->
                getInsertionPoint(message.fileGlobalHash, root.id!!, 0)
                    .doOnSuccess { ip ->
                        val h = message.fileGlobalHash
                        val bits = BitSet.valueOf(h)
                        var out = ""
                        for (b in 0..<Byte.SIZE_BITS) {
                            out += " ${if (bits.get(b)) 1 else 0}"
                        }
                        log.v("got initial insertion point ${h.toHexString()}")
                        log.v("got initial insertion point ${out}")
                    }
            }
            .flatMapCompletable { root ->
                val bundles =
                    ArrayList((0..<(message.fileGlobalHash.size * Byte.SIZE_BITS - root.pos)).map { v ->
                        MerkleBundle(
                            hash = null,
                            dirty = true
                        )
                    })

                insertBundleEntity(bundles).flatMapCompletable { ids ->
                    for ((bundle, id) in bundles.zip(ids)) {
                        bundle.id = id
                    }
                    iterativeMerkleInsert(message, root, bundles)
                        .doOnComplete { log.w("iterativeMerkleInsert complete $root") }
                }

            }
    }

//
//    fun insertMerkle(message: HashlessScatterMessage): Completable {
//        return getRootsRandom()
//            .flatMapCompletable { root ->
//                val hash = LibsodiumInterface.merkleHash(message.fileGlobalHash)
//                when(root.size) {
//                    0 -> insertBundleEntity(
//                        MerkleBundle(
//                            hash = hash,
//                            dirty = false
//                        )
//                    ).flatMapCompletable { id ->
//                        getInsertionPoint(hash, id).flatMapCompletable { point ->
//                            insertBundleEntity(
//                                MerkleBundle(
//                                    hash = byteArrayOf(), dirty = true
//                                )
//                            ).ignoreElement()
//                        }
//                    }
//                    else -> getInsertionPoint(hash, root[0].id).flatMapCompletable { point ->
//
//                    }
//                }
//        }
//    }

    @Query(
        "SELECT * FROM bundles WHERE id = (SELECT childOne FROM bundles WHERE id = :id)" +
                "OR id = (SELECT childTwo FROM bundles WHERE id = :id) ORDER BY hash ASC"
    )
    abstract fun getBundlesForBundle(id: Long): List<MerkleBundle>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertBundleEntity(bundle: MerkleBundle): Single<Long>

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

    @Transaction
    @Insert
    fun insertMessage(message: DbMessage): Completable {
        return insertGlobalHash(message.file.global)
            .andThen(
                insertMessagesEntity(message.message)
                    .flatMapCompletable { messageRes ->
                        message.message.messageID = messageRes
                        message.identity_fingerprints.forEach { f ->
                            f.message = messageRes
                        }
                        message.recipient_fingerprints.forEach { f ->
                            f.message = messageRes
                        }
                        insertIdentityIdEntity(message.identity_fingerprints)
                            .ignoreElement()
                            .andThen(insertIdentityIdEntity(message.recipient_fingerprints))
                            .ignoreElement()
                            .andThen(insertHashesEntity(message.file.messageHashes))
                            .ignoreElement()
                            .andThen(insertMerkle(message.message))
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