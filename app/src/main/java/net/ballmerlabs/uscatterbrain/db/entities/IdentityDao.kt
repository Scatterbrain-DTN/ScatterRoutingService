package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.*
import io.reactivex.Completable
import io.reactivex.Single

@Dao
interface IdentityDao {
    @get:Query("SELECT * FROM identities")
    @get:Transaction
    val all: Single<List<Identity>>

    @Transaction
    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    fun getIdentitiesWithRelations(ids: List<Long>): Single<List<Identity>>

    @Transaction
    @Query("SELECT * FROM identities WHERE fingerprint IN (:fingerprint)")
    fun getIdentityByFingerprint(fingerprint: String): Single<Identity>

    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    fun getByID(ids: List<Long>): Single<List<KeylessIdentity>>

    @Query("SELECT * FROM identities WHERE givenname IN (:names)")
    fun getByGivenName(names: Array<String>): Single<List<KeylessIdentity>>

    @Query("SELECT * FROM keys WHERE keyID IN (:ids)")
    fun getKeys(ids: List<Long>): Single<List<Keys>>

    @Transaction
    @Query("SELECT * FROM identities ORDER BY RANDOM() LIMIT :count")
    fun getTopRandom(count: Int): Single<List<Identity>>

    @Query("SELECT * FROM clientapp WHERE identityFK = (" +
            "SELECT identityID FROM identities WHERE fingerprint = :fp)")
    fun getClientApps(fp: String): Single<List<ClientApp>>

    @Query("SELECT COUNT(*) FROM identities")
    fun getNumIdentities(): Int

    @Insert
    fun insert(identity: KeylessIdentity): Single<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertClientApps(clientApps: List<ClientApp>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
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

    @Delete(entity = ClientApp::class)
    fun deleteClientApps(vararg apps: JustPackageName): Completable

    @Transaction
    @Delete(entity = KeylessIdentity::class)
    fun deleteIdentityByFingerprint(vararg fingerprint: JustFingerprint): Completable

    @Transaction
    @Delete(entity = KeylessIdentity::class)
    fun deleteIdentityByFingerprint(fingerprint: List<JustFingerprint>): Completable
}