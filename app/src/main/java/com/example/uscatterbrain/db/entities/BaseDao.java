package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;

import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Single;

@Dao
public interface BaseDao<T> {

    @Insert
    Single<List<Long>> insertAll(List<T> entites);

    @Insert
    Single<List<Long>> insertAll(T... entities);

    @Delete
    Completable delete(T entities);
}
