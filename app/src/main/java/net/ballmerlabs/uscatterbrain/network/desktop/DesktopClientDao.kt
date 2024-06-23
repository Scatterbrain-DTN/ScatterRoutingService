package net.ballmerlabs.uscatterbrain.network.desktop

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
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
}