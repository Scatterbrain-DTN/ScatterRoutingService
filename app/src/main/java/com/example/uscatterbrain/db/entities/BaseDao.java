package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;

import java.util.List;

@Dao
public interface BaseDao<T> {

    @Insert
    void insertAll(List<T> entites);

    @Insert
    void insertAll(T... entities);

    @Delete
    void delete(T entities);
}
