package com.example.uscatterbrain.db.entities;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DiskFilesDao extends BaseDao<DiskFiles>{
    @Query("SELECT * FROM diskfiles")
    List<DiskFiles> getAll();

    @Query("SELECT * FROM diskfiles")
    LiveData<List<DiskFiles>> getAllAsync();

    @Query("SELECT * FROM diskfiles WHERE fileID IN (:ids) ")
    List<DiskFiles> getById(int[] ids);

    @Query("SELECT * FROM diskfiles WHERE filepath IN (:paths)")
    List<DiskFiles> getByPath(String[] paths);

    @Insert
    List<Long> insertAll(DiskFiles... files);

    @Insert
    List<Long> insertAll(List<DiskFiles> files);

    @Delete
    void delete(DiskFiles file);
}
