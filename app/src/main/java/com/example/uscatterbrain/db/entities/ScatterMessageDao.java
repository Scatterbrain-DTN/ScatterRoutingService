package com.example.uscatterbrain.db.entities;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public abstract class ScatterMessageDao implements BaseDao<ScatterMessage> {

    @Transaction
    @Query("SELECT * FROM messages")
    public abstract LiveData<List<ScatterMessage>> getAll();

    @Transaction
    @Query("SELECT * FROM messages")
    public abstract LiveData<List<ScatterMessageRelations>> getMessagesWithFiles();

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract LiveData<List<ScatterMessage>> getByID(long[] ids);

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract LiveData<List<ScatterMessage>> getByID(List<Long> ids);

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract List<ScatterMessage> getByIDSync(long[] ids);

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract List<ScatterMessage> getByIDSync(List<Long> ids);

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract  List<ScatterMessage> getByIDSync(long ids);

    @Transaction
    @Query("SELECT * FROM messages WHERE filePath IN (:filePaths)")
    public abstract LiveData<List<ScatterMessage>> getByFilePath(String... filePaths);

    @Transaction
    @Query("SELECT filePath FROM messages")
    public abstract LiveData<List<String>> getAllFiles();

    @Transaction
    @Query("SELECT * FROM messages WHERE identityID IN (:ids)")
    public abstract LiveData<List<ScatterMessage>> getByIdentity(long ids);

    @Transaction
    @Query("SELECT * FROM messages ORDER BY RANDOM() LIMIT :count")
    public abstract LiveData<List<ScatterMessage>> getTopRandom(int count);

    @Transaction
    @Insert
    public abstract void insertMessagesWithHashes(List<MessageHashCrossRef> messagesWithHashes);

    @Insert
    public abstract List<Long> _insertMessages(List<ScatterMessage> messages);

    @Insert
    public abstract Long _insertMessages(ScatterMessage message);

    @Insert
    public abstract List<Long> insertHashes(List<Hashes> h);

    @Insert
    public abstract  Long insertIdentity(Identity identity);

    @Insert
    public abstract List<Long> insertIdentities(List<Identity> ids);

    @Delete
    public abstract void delete(ScatterMessage message);
}
