package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;

@Dao
public abstract class ScatterMessageDao implements BaseDao<ScatterMessage> {

    @Transaction
    @Query("SELECT * FROM messages")
    public abstract Maybe<List<ScatterMessage>> getAll();

    @Transaction
    @Query("SELECT * FROM messages")
    public abstract Maybe<List<ScatterMessageRelations>> getMessagesWithFiles();

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract Maybe<List<ScatterMessage>> getByID(long[] ids);

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract Maybe<List<ScatterMessage>> getByID(List<Long> ids);

    @Transaction
    @Query("SELECT * FROM messages WHERE filePath IN (:filePaths)")
    public abstract Maybe<List<ScatterMessage>> getByFilePath(String... filePaths);

    @Transaction
    @Query("SELECT filePath FROM messages")
    public abstract Maybe<List<String>> getAllFiles();

    @Transaction
    @Query("SELECT * FROM messages WHERE identityID IN (:ids)")
    public abstract Maybe<List<ScatterMessage>> getByIdentity(long ids);

    @Transaction
    @Query("SELECT * FROM messages ORDER BY RANDOM() LIMIT :count")
    public abstract Maybe<List<ScatterMessage>> getTopRandom(int count);

    @Transaction
    @Insert
    public abstract Single<List<Long>> insertMessagesWithHashes(List<MessageHashCrossRef> messagesWithHashes);

    @Insert
    public abstract Single<List<Long>> _insertMessages(List<ScatterMessage> messages);

    @Insert
    public abstract Single<Long> _insertMessages(ScatterMessage message);

    @Insert
    public abstract Single<List<Long>> insertHashes(List<Hashes> h);

    @Insert
    public abstract  Single<Long> insertIdentity(Identity identity);

    @Insert
    public abstract Single<List<Long>> insertIdentities(List<Identity> ids);

    @Delete
    public abstract Completable delete(ScatterMessage message);
}
