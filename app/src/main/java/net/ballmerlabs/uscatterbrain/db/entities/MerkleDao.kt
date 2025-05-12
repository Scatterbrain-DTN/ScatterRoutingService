package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.concurrent.AtomicBoolean
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import io.reactivex.Single
import io.reactivex.subjects.ReplaySubject
import net.ballmerlabs.uscatterbrain.db.HubResponse
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.compare
import net.ballmerlabs.uscatterbrain.util.scatterLog
import okio.ByteString.Companion.toByteString

@Dao
abstract class MerkleDao {
    private val log by scatterLog()

    @Query("SELECT * FROM messages WHERE bundle = :id ORDER BY fileGlobalHash ASC")
    abstract fun getMessagesForBundle(id: Long): List<HashlessScatterMessage>

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
        SELECT bundles.* FROM bundles, bundles AS parent 
        WHERE (parent.childOne = bundles.id OR parent.childTwo = bundles.id) AND bundles.hash != :hash
        AND (parent.childOne = :id OR parent.childTwo = :id)
        """
    )
    abstract fun getSiblingsExcludingHash(id: Long, hash: ByteArray): Single<List<MerkleBundle>>

    @Transaction
    @Query(
        """
        WITH RECURSIVE
            parent(id) AS (
                select id from bundles where id = :id AND hash NOT IN (:hashes)
                UNION ALL
                SELECT childOne FROM bundles AS child, parent 
                WHERE child.id = parent.id 
                AND (SELECT hash from bundles WHERE id = child.childOne) NOT IN (:hashes)
                UNION ALL
                SELECT childTwo FROM bundles AS child, parent 
                WHERE child.id = parent.id 
                AND (SELECT hash FROM bundles WHERE id = child.childTwo) NOT IN (:hashes)
        )
        SELECT * FROM messages INNER JOIN globalhash ON fileGlobalHash = globalhash.globalhash
            WHERE bundle IN parent
            AND (:flag IS NULL OR (SELECT COUNT(*) FROM (SELECT(:flag) INTERSECT SELECT flagKey FROM message_flags WHERE parentMessage = messageID)) > 0)
            ORDER BY fileSize ASC, shareCount ASC LIMIT :count
    """
    )
    abstract fun getTopRandomExcludingHash(
        id: Long,
        count: Int,
        hashes: List<ByteArray>,
        flag: List<Int>? = null
    ): Single<List<DbMessage>>


    @Query("SELECT * FROM bundles WHERE hash NOT IN (:hashes)")
    abstract fun testBundlesExcludingHash(hashes: List<ByteArray>): List<MerkleBundle>


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

    @Query("SELECT * FROM bundles WHERE id = :id")
    abstract fun getBundle(id: Long): MerkleBundle

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
            SELECT id, 0 FROM bundles WHERE id = :root
            UNION ALL
            SELECT childOne, pos + 1 FROM bundles, parent WHERE parent.ids = bundles.id 
            AND (childTwo IS NULL OR childOne IS NULL) AND NOT (childOne IS NOT NULL AND childTwo IS NOT NULL)
            UNION ALL
            SELECT childTwo, pos + 1 FROM bundles, parent WHERE parent.ids = bundles.id
            AND (childTwo IS NULL OR childOne IS NULL) AND NOT (childOne IS NOT NULL AND childTwo IS NOT NULL)
       ) SELECT * FROM bundles INNER JOIN parent ON ids = id ORDER BY POS DESC LIMIT 1
        """
    )
    abstract fun getNextHub(root: Long?): MerkleBundle?

    @Query("SELECT COUNT(*) FROM bundles WHERE hash = :hash")
    abstract fun getByHash(hash: ByteArray): Single<Int>

    fun getHubs(root: MerkleBundle?, remote: Flowable<ByteArray>): HubResponse {
        if (root == null)
            return HubResponse(
                hubs = Observable.empty(),
                exclude = Observable.empty()
            )
        val exclude = ReplaySubject.create<ByteArray>()
        //out.onNext(root)


        val done = AtomicBoolean(false)
        val remoteDone = AtomicBoolean(false)


        return HubResponse(
            hubs = Observable.create { obs ->
                getHubs(root, obs)
                obs.onComplete()
            }
                .doOnNext { v -> log.v("getHubs hubs ${v.id}") }
                .doFinally {
                    log.v("getHubs complete!")
                    done.set(true)
                    if (remoteDone.get())
                        exclude.onComplete()
                }.doOnNext { v -> log.v("got hub ${v.hash?.toByteString()}") },
            exclude = remote.concatMapMaybe { v ->
                getByHash(v).flatMapMaybe { count ->
                    if (count > 0)
                        Maybe.just(v)
                    else
                        Maybe.empty()

                }
            }.toObservable()
                .doFinally {
                    log.w("remote completed")
                    remoteDone.set(true)
                    if (done.get()) {
                        exclude.onComplete()
                    }
                }

        )
    }

    private fun getHubs(
        root: MerkleBundle?,
        hubs: ObservableEmitter<MerkleBundle>,
    ) {
        if (root?.hash == null)
            return
        hubs.onNext(root)
        val childOneHub = getNextHub(root.childOne)
        val childTwoHub = getNextHub(root.childTwo)

        if (childOneHub != null) {
            log.v("getHubs: ${root.id} ${childOneHub.hash?.toByteString()}")
            getHubs(childOneHub, hubs)
        }

        if (childTwoHub != null) {
            log.v("getHubs: ${root.id} ${childTwoHub.hash?.toByteString()}")
            getHubs(childTwoHub, hubs)
        }


    }


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
//        log.v("merkleRehash depth=$pos root=$root")
//        log.v("\tmhash=${mhash.map { v -> v.toHexString() }}")
//        log.v("\tbhash=${bhash.map { v -> v.toHexString() }}")
        val q = bhash + mhash
        val b = q.sortedWith { v, n -> v.compare(n) }
//        log.v("\tcombined=${b.map { v -> v.toHexString() }}")
        val hash = LibsodiumInterface.merkleHash(b)
//        log.v("\tfinal=${hash.toHexString()}")
        updateBundleHash(hash, root)
    }


    @OptIn(ExperimentalStdlibApi::class)
    fun merkleRehash(): Completable {
        return getDefaultRoot().flatMapCompletable { r ->
 //           log.v("merkleRehash start $r")
            Completable.fromAction {
                merkleRehash(r.id)
            }
        }
    }

    private fun iterativeMerkleInsert(
        message: HashlessScatterMessage,
        point: MerkleInsertCond,
        bundles: ArrayList<MerkleBundle>,
    ): Completable {
        return if (point.complete(message.fileGlobalHash)) {
            message.bundle = point.parent
            //log.v("updateParent pos=${point.pos} parent=${point.parent}")
            updateBundleForMessage(point.parent, message.messageID!!)

        } else {
            val bundle = bundles.removeLastOrNull()!!
            val isp = getInsertionPointWithoutDb(
                message.fileGlobalHash,
                bundle.id!!,
                point.pos
            )!!
//            val c = if (point.childOne)
//                "childOne=${bundle.id}"
//            else if (point.childTwo)
//                "childTwo=${bundle.id}"
//            else
//                "DIRTY"
          //  log.v("updateParent pos=${point.pos} parent=${point.parent} $c")
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
    @Query(
        """
        SELECT (INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, :pos/8 + 1, 1)), -2, 1)) * 16 
        + INSTR('123456789ABCDEF', SUBSTRING(HEX(SUBSTRING(:hash, :pos/8 + 1, 1)), -1, 1)) >> (:pos % 8)) & 1 == 0
        """
    )
    abstract fun testBitmask(hash: ByteArray, pos: Long): Boolean


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

    @Query(
        "SELECT * FROM bundles WHERE id = (SELECT childOne FROM bundles WHERE id = :id)" +
                "OR id = (SELECT childTwo FROM bundles WHERE id = :id) ORDER BY hash ASC"
    )
    abstract fun getBundlesForBundle(id: Long): List<MerkleBundle>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertBundleEntity(bundle: MerkleBundle): Single<Long>

    @OptIn(ExperimentalStdlibApi::class)
    fun insertMerkle(message: HashlessScatterMessage): Completable {
        return getDefaultRoot()
            .flatMapMaybe { root ->
                getInsertionPoint(message.fileGlobalHash, root.id!!, 0)
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
                        .doOnComplete {
                            log.v("iterativeMerkleInsert of message ${message.fileGlobalHash.toByteString()} complete $root")
                        }
                }

            }
    }


}