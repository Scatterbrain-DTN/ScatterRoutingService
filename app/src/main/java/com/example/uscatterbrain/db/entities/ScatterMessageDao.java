package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.ArrayList;
import java.util.List;

@Dao
public abstract class ScatterMessageDao {

    @Transaction
    @Query("SELECT * FROM messages")
    public abstract List<ScatterMessage> getAll();

    @Transaction
    @Query("SELECT * FROM messages")
    public abstract List<ScatterMessagesWithFiles> getMessagesWithFiles();

    @Transaction
    @Query("SELECT * FROM messages WHERE messageID IN (:ids)")
    public abstract List<ScatterMessage> getByID(int[] ids);

    @Transaction
    @Query("SELECT * FROM messages ORDER BY RANDOM() LIMIT :count")
    public abstract List<ScatterMessage> getTopRandom(int count);

    public void insertMessages(ScatterMessage message) {
        Long id = _insertMessages(message);

        List<MessageDiskFileCrossRef> xrefs = new ArrayList<>();

        List<Long> fileids = insertDiskFiles(message.files);
        for(Long fileID : fileids) {
            MessageDiskFileCrossRef xref = new MessageDiskFileCrossRef();
            xref.messageID = id;
            xref.fileID = fileID;
            xrefs.add(xref);
        }
        Long identityID = insertIdentity(message.identity);
        message.identityID = identityID;

        insertMessagesWithFiles(xrefs);
    }

    public void insertMessages(List<ScatterMessage> messages) {
        List<Long> ids =  _insertMessages(messages);

        List<MessageDiskFileCrossRef> xrefs = new ArrayList<>();

        for(ScatterMessage message : messages) {
            List<Long> fileids = insertDiskFiles(message.files);
            for(Long messageID : ids) {
                for(Long fileID : fileids) {
                    MessageDiskFileCrossRef xref = new MessageDiskFileCrossRef();
                    xref.messageID = messageID;
                    xref.fileID = fileID;
                    xrefs.add(xref);
                }
            }
            Long identityID = insertIdentity(message.identity);
            message.identityID = identityID;
        }

        insertMessagesWithFiles(xrefs);

    }

    @Transaction
    @Insert
    public abstract void insertMessagesWithFiles(List<MessageDiskFileCrossRef> messagesWithFiles);

    @Insert
    public abstract List<Long> _insertMessages(List<ScatterMessage> messages);

    @Insert
    public abstract Long _insertMessages(ScatterMessage message);

    @Insert
    public abstract List<Long> insertDiskFiles(List<DiskFiles> df);

    @Insert
    public abstract  Long insertIdentity(Identity identity);

    @Insert
    public abstract List<Long> insertIdentities(List<Identity> ids);

    @Delete
    public abstract void delete(ScatterMessage message);
}
