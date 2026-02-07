package net.ballmerlabs.uscatterbrain.db.entities

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.concurrent.AtomicInt
import com.geeksville.mesh.util.toHexString
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.uscatterbrain.db.HubResponse
import net.ballmerlabs.uscatterbrain.db.MerkleElement
import net.ballmerlabs.uscatterbrain.db.MerkleNode
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.compare
import net.ballmerlabs.uscatterbrain.util.QueueSubject
import net.ballmerlabs.uscatterbrain.util.scatterLog
import okio.withLock
import java.util.concurrent.locks.ReentrantLock

@Dao
abstract class MerkleDao {
    private val log by scatterLog()

    private val lock = ReentrantLock()


    @Query("SELECT * FROM messages WHERE bundle = :id ORDER BY fileGlobalHash ASC")
    abstract fun getMessagesForBundle(id: Long): List<HashlessScatterMessage>


    @Query("SELECT * FROM messages ORDER BY fileGlobalHash LIMIT :limit OFFSET :offset")
    abstract fun getMessagesLimitOffset(limit: Int, offset: Int): List<HashlessScatterMessage>

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
            parent(id) AS (
                select id from bundles where id = :id
                UNION ALL
                SELECT childOne FROM bundles, parent WHERE bundles.id = parent.id 
                UNION ALL
                SELECT childTwo FROM bundles, parent WHERE bundles.id = parent.id
        )
        SELECT * FROM bundles, parent where bundles.id = parent.id
    """
    )
    abstract fun getBundlesRecursive(id: Long): List<MerkleBundle>

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
            AND (:fileSize IS NULL OR fileSize <= :fileSize)
            ORDER BY fileSize ASC, shareCount ASC LIMIT :count
    """
    )
    abstract fun getTopRandomExcludingHash(
        id: Long,
        count: Int,
        hashes: List<ByteArray>,
        flag: List<Int>? = null,
        fileSize: Long? = null,
    ): Single<List<DbMessage>>

    @Query("SELECT id FROM bundles WHERE hash NOT IN (:hashes)")
    abstract fun getNotHashes(hashes: List<ByteArray>): Single<List<Long>>

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
    abstract fun getInsertionPoint(hash: ByteArray, root: Long, pos: Long): MerkleInsertCond


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

    @Insert
    abstract fun insertBundleEntitySync(bundles: List<MerkleBundle>): List<Long>

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
    )

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
    )

    @Query("SELECT hash from bundles")
    abstract fun getAllHashes(): List<ByteArray>

    @Query("UPDATE messages SET bundle = :bundle WHERE messageID = :messageID")
    abstract fun updateBundleForMessage(bundle: Long, messageID: Long)

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

    @Query("SELECT COUNT(*) FROM messages")
    abstract fun getMessageCount(): Single<Long>

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
            AND childTwo IS NULL
            UNION ALL
            SELECT childTwo, pos + 1 FROM bundles, parent WHERE parent.ids = bundles.id
            AND childOne IS NULL
       ) SELECT * FROM bundles INNER JOIN parent ON ids = id ORDER BY POS DESC LIMIT 1
        """
    )
    abstract fun getNextHub(root: Long?): MerkleBundle?

    @Query("SELECT COUNT(*) FROM bundles WHERE hash = :hash")
    abstract fun getByHash(hash: ByteArray): Single<Int>


    class RemoteItem(
        val item: ByteArray?,
    )


    fun getAllHubs(
        root: MerkleBundle?,
        list: MutableList<ByteArray> = mutableListOf(),
    ): List<ByteArray> {
        if (root?.hash == null)
            return list
        list.add(root.hash!!)
        //      val childOneHub = if (root.childOne != null ) getBundle(root.childOne!!) else null
        //     val childTwoHub = if (root.childTwo != null) getBundle(root.childTwo!!) else null
        val childOneHub = getNextHub(root.childOne)
        val childTwoHub = getNextHub(root.childTwo)
        if (childOneHub != null) {
            //    log.v("getHubs: ${root.id} ${childOneHub.hash?.toHexString()}")
            getAllHubs(childOneHub, list)
        }

        if (childTwoHub != null) {
            //  log.v("getHubs: ${root.id} ${childTwoHub.hash?.toHexString()}")
            getAllHubs(childTwoHub, list)
        }

        return list
    }


    fun getHubs(root: MerkleBundle?, remote: Flowable<ByteArray>, scheduler: Scheduler, limit: Int? = null): HubResponse {
        if (root == null)
            return HubResponse(
                hubs = Flowable.empty(),
                exclude = Flowable.empty()
            )
        val exclude = mutableListOf<ByteArray>()
        //out.onNext(root)
//        val rs = PublishProcessor.create<RemoteItem>()
//        val p = remote.map { v -> RemoteItem(v) }.publish()
//        p.subscribe(rs)

        val remoteComplete = CompletableSubject.create()
        val rs = QueueSubject<RemoteItem>()
        remote.map { item -> RemoteItem(item) }
            .doFinally {
                remoteComplete.onComplete()
            }
            .subscribe(rs)

        val hubsComplete = CompletableSubject.create()
        val hubs = Flowable.create({ obs ->
            //    p.connect()
            getHubs(
                root,
                root,
                obs,
                rs,
                exclude,
                mutableSetOf(),
                mutableSetOf(),
                AtomicInt(0),
                scheduler,
                limit
            )
            obs.onComplete()
        }, BackpressureStrategy.BUFFER)
            //     .doOnNext { v -> log.v("getHubs hubs ${v.id}") }
            .doFinally {
                log.v("getHubs complete!")
                hubsComplete.onComplete()
            }
        return HubResponse(
            hubs = hubs,
            exclude = remoteComplete.andThen(hubsComplete)
                .andThen(Flowable.defer {
                    Flowable.fromIterable(exclude)
                })
                .doFinally {
                    log.w("remote completed")
                }

        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun getHubs(
        permaRoot: MerkleBundle?,
        root: MerkleBundle?,
        hubs: FlowableEmitter<MerkleElement>,
        remote: QueueSubject<RemoteItem>,
        exclude: MutableList<ByteArray>,
        nextTheirs: MutableSet<String>,
        nextOurs: MutableSet<String>,
        count: AtomicInt = AtomicInt(0),
        scheduler: Scheduler,
        target: Int? = null,
    ) {
        val c = count.getAndIncrement()
        if (root?.hash == null || permaRoot?.hash == null || (target != null && c >= target))
            return
        val item = if (remote.hasItem()) {
            remote.get()
                .toObservable()
                .subscribeOn(scheduler)
                .mergeWith(Completable.fromAction {
                    hubs.onNext(
                        MerkleElement(
                            bundle = root,
                        )
                    )
                })
                .firstElement()
                .blockingGet()
        } else {
            log.v("sending regular")
            hubs.onNext(MerkleElement(bundle = root))
            null
        }

        val hash = root.hash!!
        //val childOneHub = if (root.childOne != null ) getBundle(root.childOne!!) else null
        //val childTwoHub = if (root.childTwo != null) getBundle(root.childTwo!!) else null
        log.v("comparing hash ${item?.item?.toHexString()}, ${hash.toHexString()}")
        val childOneHub = getNextHub(root.childOne)
        val childTwoHub = getNextHub(root.childTwo)
        if (
            (item?.item != null && item.item.contentEquals(hash)) ||
            nextOurs.contains(hash.toHexString()) ||
            nextTheirs.contains(item?.item?.toHexString())
        ) {
            log.w("MATCH! on ${hash.toHexString()}")
            exclude.add(hash)
            return
        }

        if (item?.item != null) {
            nextOurs.add(item.item.toHexString())
        }
        nextTheirs.add(hash.toHexString())




        if (childOneHub != null) {
            //    log.v("getHubs: ${root.id} ${childOneHub.hash?.toHexString()}")
            getHubs(
                permaRoot,
                childOneHub,
                hubs,
                remote,
                exclude,
                nextTheirs,
                nextOurs,
                count,
                scheduler,
                target
            )
        }

        if (childTwoHub != null) {
            //  log.v("getHubs: ${root.id} ${childTwoHub.hash?.toHexString()}")
            getHubs(
                permaRoot,
                childTwoHub,
                hubs,
                remote,
                exclude,
                nextTheirs,
                nextOurs,
                count,
                scheduler,
                target
            )
        }


    }

//    private fun getEndHash(root: MerkleBundle, skip: Set<String>): ByteArray? {
//        val childOneHub = getNextHub(root.childOne)
//        val childTwoHub = getNextHub(root.childTwo)
//        var end = root.hash
//        if (childOneHub != null && !skip.contains(childOneHub.hash?.toHexString())) {
//            val h = getEndHash(childOneHub, skip)
//            if (h != null)
//                end = h
//        }
//
//        if (childTwoHub != null && !skip.contains(childTwoHub.hash?.toHexString())) {
//            val h = getEndHash(childTwoHub, skip)
//            if (h != null)
//                end = h
//        }
//
//        return end
//    }


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


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun bulkReplaceBundle(bundles: List<MerkleBundle>)

   open fun merkleRehash(root: Long?, pos: Long = 0) {
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


    open fun merkleRehashInMemory(memoryTree: MerkleNode?) {
        if (memoryTree == null) {
            return
        }
        merkleRehashInMemory(memoryTree.childOne)
        merkleRehashInMemory(memoryTree.childTwo)

        val root = memoryTree.bundle.id ?: return
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
        memoryTree.bundle.hash = hash
        memoryTree.bundle.dirty = false

    }

    open fun merkleRehashInMemory(root: Long?, pos: Long = 0) {
        if (root == null) {
            return
        }

        val bundles = getBundlesRecursive(root)

        val memoryTree = MerkleNode.fromBundles(bundles, root)

        merkleRehashInMemory(memoryTree)

        bulkReplaceBundle(bundles)
    }

    open fun merkleRehash(scheduler: Scheduler): Completable {
        return getDefaultRoot()
            .flatMapCompletable { r ->
                Completable.fromAction {
                    lock.withLock {
                        merkleRehash(r.id)
                    }
                }.subscribeOn(scheduler)
            }
    }
    
    open fun merkleRehashInMemory(scheduler: Scheduler): Completable {
        return getDefaultRoot()
            .flatMapCompletable { r ->
                Completable.fromAction {
                    lock.withLock {
                        merkleRehashInMemory(r.id)
                    }
                }.subscribeOn(scheduler)
            }
    }

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

    @Query("DELETE FROM bundles")
    abstract fun nukeAllBundles(): Completable

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertBundleEntity(bundle: MerkleBundle): Single<Long>

}