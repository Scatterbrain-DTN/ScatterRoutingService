package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.*
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import java.util.*

/**
 * Room dao for operations on identities and permission ACLs
 * stored in database.
 */
@Dao
interface IdentityDao {
    @get:Query("SELECT * FROM identities")
    val all: Single<List<Identity>>

    @Transaction
    @Query("SELECT * FROM identities WHERE identityID IN (:ids) AND (privatekey IS NOT NULL) = :owned ")
    fun getIdentitiesWithRelations(ids: List<Long>, owned: Boolean): Single<List<Identity>>

    @Transaction
    @Query("SELECT * FROM identities WHERE fingerprint IN (:fingerprint)")
    fun getIdentityByFingerprint(fingerprint: UUID): Single<Identity>

    @Transaction
    @Query("SELECT * FROM identities WHERE fingerprint IN (:fingerprint)")
    fun getIdentityByFingerprintMaybe(fingerprint: UUID): Maybe<Identity>

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
    fun getClientApps(fp: UUID): Single<List<ClientApp>>

    @Query("SELECT * FROM clientapp")
    fun getClientApps(): Single<List<ClientApp>>

    @Query("SELECT packageName FROM clientapp")
    fun getAllPackageNames(): Single<List<String>>

    @Query("SELECT COUNT(*) FROM identities")
    fun getNumIdentities(): Single<Int>

    @Insert
    fun insert(identity: KeylessIdentity): Single<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertClientAppsIgnore(clientApps: List<ClientApp>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertClientAppIgnore(vararg apps: ClientApp): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertClientAppsReplace(clientApps: List<ClientApp>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertClientAppReplace(vararg apps: ClientApp): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun __insertAll(identities: KeylessIdentity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun __insertKeys(keys: List<Keys>): List<Long>


    @Transaction
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIdentity(identity: Identity): Long {
        val identityID = __insertAll(identity.identity)
        for (key in identity.keys) {
            key.identityFK = identityID
        }
        __insertKeys(identity.keys)
        return identityID
    }

    @Delete
    fun delete(identity: KeylessIdentity): Completable

    @Delete(entity = ClientApp::class)
    fun deleteBySignature(packageSig: JustPackageSig): Completable

    @Delete(entity = ClientApp::class)
    fun deleteClientApps(vararg apps: JustPackageName): Completable

    @Transaction
    @Delete(entity = KeylessIdentity::class)
    fun deleteIdentityByFingerprint(vararg fingerprint: JustFingerprint): Completable

    @Transaction
    @Delete(entity = KeylessIdentity::class)
    fun deleteIdentityByFingerprint(fingerprint: List<JustFingerprint>): Completable
}