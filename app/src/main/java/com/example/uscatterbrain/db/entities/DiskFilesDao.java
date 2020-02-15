package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DiskFilesDao {
    @Query("SELECT * FROM diskfiles")
    List<DiskFiles> getAll();

    @Query("SELECT * FROM diskfiles WHERE fileID IN (:ids) ")
    List<DiskFiles> getById(int[] ids);

    @Query("SELECT * FROM diskfiles WHERE filepath IN (:paths)")
    List<DiskFiles> getByPath(String[] paths);

    @Insert
    void insertAll(DiskFiles... files);

    @Insert
    void insertAll(List<DiskFiles> files);

    @Delete
    void delete(DiskFiles file);
}
