package com.example.uscatterbrain.db.entities;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;

@Dao
public interface IdentityDao extends BaseDao<Identity> {
    @Query("SELECT * FROM identities")
    Maybe<List<Identity>> getAll();

    @Transaction
    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    Maybe<List<IdentityRelations>> getIdentitiesWithRelations(List<Long> ids);

    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    Maybe<List<Identity>> getByID(List<Long> ids);

    @Query("SELECT * FROM identities WHERE givenname IN (:names)")
    Maybe<List<Identity>> getByGivenName(String[] names);

    @Query("SELECT * FROM keys WHERE keyID IN (:ids)")
    Maybe<List<Keys>> getKeys(List<Long> ids);

    @Insert
    Single<List<Long>> insertAll(Identity... identities);

    @Insert
    Single<List<Long>> insertAll(List<Identity> identities);

    @Insert
    Single<List<Long>> insertHashes(List<Hashes> hashes);

    @Insert
    Single<List<Long>> insertKeys(List<Keys> keys);

    @Delete
    Completable delete(Identity identity);
}
