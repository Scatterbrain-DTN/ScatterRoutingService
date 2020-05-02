package com.example.uscatterbrain.db.entities;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface IdentityDao extends BaseDao<Identity> {
    @Query("SELECT * FROM identities")
    LiveData<List<Identity>> getAll();

    @Transaction
    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    List<IdentityRelations> getIdentitiesWithRelations(List<Long> ids);

    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    List<Identity> getByID(List<Long> ids);

    @Query("SELECT * FROM identities WHERE givenname IN (:names)")
    LiveData<List<Identity>> getByGivenName(String[] names);

    @Query("SELECT * FROM keys WHERE keyID IN (:ids)")
    List<Keys> getKeys(List<Long> ids);

    @Insert
    List<Long> insertAll(Identity... identities);

    @Insert
    List<Long> insertAll(List<Identity> identities);

    @Insert
    List<Long> insertHashes(List<Hashes> hashes);

    @Insert
    List<Long> insertKeys(List<Keys> keys);

    @Delete
    void delete(Identity identity);
}
