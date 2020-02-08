package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface ScatterMessageWithFilesDao {
    @Transaction
    @Query("SELECT * FROM messages")
    List<ScatterMessagesWithFiles> getAll();

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    List<ScatterMessagesWithFiles> getByID(int[] ids);

    @Transaction
    @Query("SELECT * FROM messages ORDER BY RANDOM() LIMIT :count")
    List<ScatterMessagesWithFiles> getTopRandom(int count);
}
