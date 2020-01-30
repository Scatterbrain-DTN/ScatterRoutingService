package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ScatterMessageDao {
    @Query("SELECT * FROM messages")
    List<ScatterMessage> getAll();

    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    List<ScatterMessage> getByID(int[] ids);

    @Insert
    void insertAll(ScatterMessage... messages);

    @Delete
    void delete(ScatterMessage message);
}
