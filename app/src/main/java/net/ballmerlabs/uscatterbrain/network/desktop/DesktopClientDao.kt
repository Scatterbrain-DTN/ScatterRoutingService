package net.ballmerlabs.uscatterbrain.network.desktop

import android.util.Log
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.goterl.lazysodium.interfaces.KeyExchange
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.db.entities.ClientApp
import net.ballmerlabs.uscatterbrain.db.entities.JustFingerprint
import net.ballmerlabs.uscatterbrain.db.entities.JustPackageName
import net.ballmerlabs.uscatterbrain.db.entities.JustPackageSig
import net.ballmerlabs.uscatterbrain.network.b64
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
import net.ballmerlabs.uscatterbrain.network.fingerprint
import java.util.UUID

@Dao
interface DesktopClientDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @Transaction
    fun upsertClient(pubkey: ByteArray, kp: PublicKeyPair): Single<DesktopClient> {
        return getClientByPubkey(pubkey)
            .switchIfEmpty(Single.fromCallable {
                DesktopClient(
                    session = UUID.randomUUID(),
                    key = kp.privkey,
                    name = "",
                    pubkey = kp.pubkey,
                    remotekey = pubkey,
                    paired = false
                )
            }.flatMap { v -> insertClient(v).toSingleDefault(v) })

    }

    @Update
    fun updateClient(client: DesktopClient): Completable

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertClient(client: DesktopClient): Completable

    @Query("SELECT * FROM desktop_clients WHERE session = :session")
    fun getClientById(session: UUID): Single<DesktopClient>

    @Query("SELECT * FROM desktop_clients WHERE remotekey = :pubkey")
    fun getClientByPubkey(pubkey: ByteArray): Maybe<DesktopClient>

    @Query("SELECT * FROM desktop_clients WHERE remote_fingerprint = :fingerprint")
    fun getClientByFingerprint(fingerprint: ByteArray): Maybe<DesktopClient>

    @Delete
    fun deleteClient(client: DesktopClient): Completable

    @Query("DELETE FROM desktop_clients WHERE remotekey = :remotekey")
    fun deleteByRemoteKey(remotekey: ByteArray): Completable

    @Query("DELETE FROM desktop_clients WHERE remote_fingerprint = :remotekey")
    fun deleteByFingerprint(remotekey: ByteArray): Completable

    @Delete(entity = ClientApp::class)
    fun deleteBySignature(packageSig: JustPackageSig): Completable

    @Delete(entity = ClientApp::class)
    fun deleteClientApps(vararg apps: JustPackageName): Completable

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertClientAppsIgnore(clientApps: List<ClientApp>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertClientAppIgnore(vararg apps: ClientApp): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertClientAppsReplace(clientApps: List<ClientApp>): Single<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertClientAppReplace(vararg apps: ClientApp): Single<List<Long>>


    @Query("SELECT * FROM clientapp")
    fun getClientApps(): Single<List<ClientApp>>

    @Query("SELECT * FROM desktop_clients")
    fun getDesktopApps(): Single<List<DesktopClient>>

    @Update
    fun updateDesktopClients(desktopClient: DesktopClient): Completable

    @Query(
        "SELECT * FROM clientapp WHERE identityFK = (" +
                "SELECT identityID FROM identities WHERE fingerprint = :fp)"
    )
    fun getClientApps(fp: UUID): Single<List<ClientApp>>


    @Transaction
    fun deleteAndGet(remotekey: ByteArray) {
        getClientByFingerprint(remotekey)
            .switchIfEmpty(getClientByPubkey(remotekey))
            .flatMapCompletable { v ->
                val fingerprint = v.remotekey.fingerprint()
                val key = v.remotekey.b64()
                Log.v("debug", "got remotekey $fingerprint")
//                deleteBySignature(
//                    JustPackageSig(
//                        packageSignature = fingerprint.b64()
//                    )
//                ).andThen(
//                    deleteBySignature(JustPackageSig(
//                        packageSignature = key
//                    ))
//                ).andThen(
                    deleteByRemoteKey(v.remotekey)
                    .andThen(deleteByRemoteKey(fingerprint))

            }
            .blockingAwait()
    }
}