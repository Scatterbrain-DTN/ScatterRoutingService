package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MessageDiskFileCrossRefDao {

    @Query("SELECT * FROM MessageDiskFileCrossRef")
    List<MessageDiskFileCrossRef> getAll();

    @Insert
    void insertAll(List<MessageDiskFileCrossRef> messageDiskFileCrossRefs);
}
