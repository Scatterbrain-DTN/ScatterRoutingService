package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface IdentityDao {
    @Query("SELECT * FROM identities")
    List<Identity> getAll();

    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    List<Identity> getByID(int[] ids);

    @Query("SELECT * FROM identities WHERE givenname IN (:names)")
    List<Identity> getByGivenName(String[] names);

    @Insert
    void insertAll(Identity... identities);

    @Delete
    void delete(Identity identity);
}
