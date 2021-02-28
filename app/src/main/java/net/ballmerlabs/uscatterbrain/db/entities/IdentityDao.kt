package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.*
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single

@Dao
interface IdentityDao {
    @get:Query("SELECT * FROM identities")
    @get:Transaction
    val all: Single<List<Identity>>

    @Transaction
    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    fun getIdentitiesWithRelations(ids: List<Long>): Maybe<List<Identity>>

    @Transaction
    @Query("SELECT * FROM identities WHERE fingerprint IN (:fingerprint)")
    fun getIdentityByFingerprint(fingerprint: String): Maybe<Identity>

    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    fun getByID(ids: List<Long>): Maybe<List<KeylessIdentity>>

    @Query("SELECT * FROM identities WHERE givenname IN (:names)")
    fun getByGivenName(names: Array<String>): Maybe<List<KeylessIdentity>>

    @Query("SELECT * FROM keys WHERE keyID IN (:ids)")
    fun getKeys(ids: List<Long>): Maybe<List<Keys>>

    @Transaction
    @Query("SELECT * FROM identities ORDER BY RANDOM() LIMIT :count")
    fun getTopRandom(count: Int): Single<List<Identity>>

    @Query("SELECT * FROM clientapp WHERE identityFK = (" +
            "SELECT identityID FROM identities WHERE fingerprint = :fp)")
    fun getClientApps(fp: String): Single<List<ClientApp>>

    @get:Query("SELECT COUNT(*) FROM identities")
    val identityCount: Int

    @Insert
    fun insert(identity: KeylessIdentity): Single<Long>

    @Insert
    fun insertClientApps(clientApps: List<ClientApp>): Single<List<Long>>

    @Insert
    fun insertClientApp(vararg apps: ClientApp): Completable

    @Insert
    fun insertAll(vararg identities: KeylessIdentity): Single<List<Long>>

    @Insert
    fun insertAll(identities: List<KeylessIdentity>): Single<List<Long>>

    @Insert
    fun insertHashes(hashes: List<Hashes>): Single<List<Long>>

    @Insert
    fun insertKeys(keys: List<Keys>): Single<List<Long>>

    @Delete
    fun delete(identity: KeylessIdentity): Completable

    @Delete
    fun deleteClientApps(vararg apps: ClientApp): Completable
}