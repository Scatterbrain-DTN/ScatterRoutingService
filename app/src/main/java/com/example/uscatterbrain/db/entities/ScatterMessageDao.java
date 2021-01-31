package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import java.net.PortUnreachableException;
import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;

@Dao
public abstract class ScatterMessageDao {

    @Transaction
    @Query("SELECT * FROM messages")
    public abstract Maybe<List<ScatterMessage>> getAll();

    @Transaction
    @Query("SELECT * FROM messages")
    public abstract Maybe<List<ScatterMessage>> getMessagesWithFiles();

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract Single<ScatterMessage> getByID(long... ids);

    @Transaction
    @Query("SELECT * FROM messages WHERE filePath IN (:filePaths)")
    public abstract Maybe<List<ScatterMessage>> getByFilePath(String... filePaths);

    @Transaction
    @Query("SELECT * FROM messages where application IN (:application)")
    public abstract Single<List<ScatterMessage>> getByApplication(String application);

    @Transaction
    @Query("SELECT filePath FROM messages")
    public abstract Maybe<List<String>> getAllFiles();

    @Transaction
    @Query("SELECT * FROM messages WHERE identity_fingerprint IN (:ids)")
    public abstract Maybe<List<ScatterMessage>> getByIdentity(String ids);

    @Transaction
    @Query("SELECT * FROM messages ORDER BY RANDOM() LIMIT :count")
    public abstract Single<List<ScatterMessage>> getTopRandom(int count);

    @Transaction
    @Query(
            "SELECT * FROM messages WHERE globalhash NOT IN (:globalhashes)" +
                    "ORDER BY RANDOM() LIMIT :count"
    )
    public abstract Single<List<ScatterMessage>> getTopRandomExclusingHash(int count, List<byte[]> globalhashes);

    @Query("SELECT globalhash FROM messages ORDER BY RANDOM() LIMIT :count")
    public abstract Single<List<byte[]>> getTopHashes(int count);

    @Transaction
    @Insert
    public abstract Single<List<Long>> insertMessagesWithHashes(List<MessageHashCrossRef> messagesWithHashes);

    @Insert
    public abstract Single<List<Long>> _insertMessages(List<HashlessScatterMessage> messages);

    @Insert
    public abstract Single<Long> _insertMessages(HashlessScatterMessage message);

    @Insert
    public abstract Single<List<Long>> insertHashes(List<Hashes> h);

    @Insert
    public abstract  Single<Long> insertIdentity(KeylessIdentity identity);

    @Insert
    public abstract Single<List<Long>> insertIdentities(List<KeylessIdentity> ids);

    @Delete
    public abstract Completable delete(HashlessScatterMessage message);

    @Query("DELETE FROM messages WHERE filepath = :path")
    public abstract int deleteByPath(String path);


    @Query("SELECT COUNT(*) FROM messages")
    public abstract int messageCount();

    @Query("SELECT COUNT(*) FROM messages WHERE filepath = :path")
    public abstract int messageCount(String path);

    @Query("SELECT COUNT(*) FROM messages WHERE filepath = :path")
    public abstract Single<Integer> messageCountSingle(String path);
}
