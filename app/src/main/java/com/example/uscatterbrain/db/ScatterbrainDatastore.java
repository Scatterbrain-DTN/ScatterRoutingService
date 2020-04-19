package com.example.uscatterbrain.db;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.room.Room;

import com.example.uscatterbrain.db.entities.DiskFiles;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.MessageDiskFileCrossRef;
import com.example.uscatterbrain.db.entities.MessageHashCrossRef;
import com.example.uscatterbrain.db.entities.ScatterMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Interface to the androidx room backed datastore
 * used for storing messages, identities, and other metadata.
 */
public class ScatterbrainDatastore {

    private Datastore mDatastore;
    private Executor executor;
    private static ScatterbrainDatastore singleton = null;
    public static final String DATABASE_NAME = "scatterdb";
    /**
     * constructor
     * @param ctx  application or service context
     */
    public ScatterbrainDatastore(Context ctx) {
        mDatastore = Room.databaseBuilder(ctx, Datastore.class, DATABASE_NAME).build();
        executor = Executors.newSingleThreadExecutor();
    }

    public static ScatterbrainDatastore getInstance() {
        if(singleton == null)
            return null;
        else
            return singleton;
    }


    /**
     * initialize the singleton
     * @param ctx application or service context
     */
    public static void initialize(Context ctx) {
        if(singleton == null)
            singleton = new ScatterbrainDatastore(ctx);
    }


    /**
     * asynchronously inserts a lit of messages into the database without
     * waiting for the result
     *
     * @param messages  list of room entities for messages to insert
     * @throws DatastoreInsertException  thrown if inner classes of message object are null
     */
    public void insertMessage(List<ScatterMessage> messages) throws DatastoreInsertException {
        insertMessage(messages, rowids -> {
            //noop
        });
    }


    /**
     *  For internal use, synchronously inserts messages to database
     * @param message room entity for message to insert
     * @return primary keys of message inserted
     */
    public Long insertMessages(ScatterMessage message) {
        Long id = this.mDatastore.scatterMessageDao()._insertMessages(message);

        List<MessageDiskFileCrossRef> xrefs = new ArrayList<>();
        List<MessageHashCrossRef> hashes = new ArrayList<>();

        List<Long> hashids = this.mDatastore.scatterMessageDao().insertHashes(message.getHashes());
        for (Long hashID : hashids) {
            MessageHashCrossRef xref = new MessageHashCrossRef();
            xref.messageID = id;
            xref.hashID = hashID;
            hashes.add(xref);
        }

        List<Long> fileids = this.mDatastore.scatterMessageDao().insertDiskFiles(message.getFiles());
        for (Long fileID : fileids) {
            MessageDiskFileCrossRef xref = new MessageDiskFileCrossRef();
            xref.messageID = id;
            xref.fileID = fileID;
            xrefs.add(xref);
        }
        Long identityID = this.mDatastore.scatterMessageDao().insertIdentity(message.getIdentity());
        message.setIdentityID(identityID);

        this.mDatastore.scatterMessageDao().insertMessagesWithFiles(xrefs);
        this.mDatastore.scatterMessageDao().insertMessagesWithHashes(hashes);
        return id;
    }

    /**
     * For internal use, synchronously inserts messages into the database
     * @param messages list of room entities to insert
     * @return list of primary keys for rows inserted
     */
    private List<Long> insertMessages(List<ScatterMessage> messages) {
        List<Long> ids =  this.mDatastore.scatterMessageDao()._insertMessages(messages);

        List<MessageDiskFileCrossRef> xrefs = new ArrayList<>();
        List<MessageHashCrossRef> hashes = new ArrayList<>();

        Iterator<Long> idItr = ids.iterator();
        Iterator<ScatterMessage> messageItr = messages.iterator();
        while (idItr.hasNext() && messageItr.hasNext()) {
            ScatterMessage message = messageItr.next();
            Long id = idItr.next();
            List<Long> fileids = this.mDatastore.scatterMessageDao().insertDiskFiles(message.getFiles());
            List<Long> hashids = this.mDatastore.scatterMessageDao().insertHashes(message.getHashes());
            for (Long fileID : fileids) {
                MessageDiskFileCrossRef xref = new MessageDiskFileCrossRef();
                xref.messageID = id;
                xref.fileID = fileID;
                xrefs.add(xref);
            }

            for (Long hashID : hashids) {
                MessageHashCrossRef xref = new MessageHashCrossRef();
                xref.messageID = id;
                xref.hashID = hashID;
                hashes.add(xref);
            }

            Long identityID = this.mDatastore.scatterMessageDao().insertIdentity(message.getIdentity());
            message.setIdentityID(identityID);
        }

        this.mDatastore.scatterMessageDao().insertMessagesWithFiles(xrefs);
        this.mDatastore.scatterMessageDao().insertMessagesWithHashes(hashes);

        return ids;
    }


    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param messages room entities to insert
     * @param callback callback object to retrieve list of primary keys on successful insert
     * @throws DatastoreInsertException
     */
    public void insertMessage(List<ScatterMessage> messages, DatastoreInsertUpdateCallback<List<Long>> callback) throws DatastoreInsertException {
        for(ScatterMessage message : messages) {
            if (message.getIdentity() == null || message.getFiles() == null || message.getHashes() == null) {
                throw new DatastoreInsertException();
            }
        }

        executor.execute(() -> {
            List<Long> ids = insertMessages(messages);
            callback.onRowUpdate(ids);
        });
    }

    /**
     * Asynchronously inserts messages into the datastore
     *
     * @param message room entities to insert
     * @throws DatastoreInsertException thrown if inner classes are null
     */
    public void insertMessage(ScatterMessage message) throws DatastoreInsertException {
        insertMessage(message, rowids -> {
            //noop
        });
    }

    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param message room entity to insert
     * @param callback callback object to retrieve primary key on successful insert
     * @throws DatastoreInsertException thrown if inner classes are null
     */
    public void insertMessage(ScatterMessage message, DatastoreInsertUpdateCallback<Long> callback) throws DatastoreInsertException {
        if(message.getIdentity() == null || message.getFiles() == null || message.getHashes() == null) {
            throw new DatastoreInsertException();
        }

        executor.execute(() -> {
            Long id = insertMessages(message);
            callback.onRowUpdate(id);
        });
    }

    /**
     * Asynchronously inserts identities into the datastore
     *
     * @param identities list of room entities to insert
     */
    public void insertIdentity(Identity[] identities) {
        insertIdentity(identities, rowids -> {
            //noop
        });
    }

    /**
     * Asynchronously inserts a list of identities into the datastore, allows tracking result
     * via provided callback
     *
     * @param identities list of room entities to insert
     * @param callback callback to retrive primary key on successful insert
     */
    public  void insertIdentity(Identity[] identities, DatastoreInsertUpdateCallback<List<Long>> callback) {
        executor.execute(() -> {
            List<Long> ids = mDatastore.identityDao().insertAll(identities);
            callback.onRowUpdate(ids);
        });
    }

    /**
     * Asynchronously inserts an identity into the datastore.
     *
     * @param identity room entity to insert
     */
    public void insertIdentity(Identity identity) {
        insertIdentity(identity, rowids -> {
            //noop
        });
    }

    /**
     * Asynchronously inserts an identity into the datastore, allows tracking result
     * via provided callback
     *
     * @param identity room entity to insert
     * @param callback callback to retrieve primary key on successful insert
     */
    public void insertIdentity(Identity identity, DatastoreInsertUpdateCallback<Long> callback) {
        executor.execute(() -> {
            List<Long> ids = mDatastore.identityDao().insertAll(identity);
            callback.onRowUpdate(ids.get(0));
        });
    }

    /**
     * Asynchronously inserts a disk file record into the datastore
     *
     * @param files room entity to insert
     */
    public void insertFile(DiskFiles files) {
        insertFile(files, rowids -> {
            //noop
        });
    }

    /**
     * Asynchronously inserts a isk file record into the datastore, allows tracking result
     * via provided callback
     *
     * @param files room entity to insert
     * @param callback callback to retrieve primary key on successful insert
     */
    public void insertFile(DiskFiles files, DatastoreInsertUpdateCallback<Long> callback) {
        executor.execute(() -> {
            List<Long> ids = mDatastore.diskFilesDao().insertAll(files);
            callback.onRowUpdate(ids.get(0));
        });
    }

    /**
     * Asynchronously inserts a list of disk file records into the datastore
     *
     * @param files list of room entities to insert
     */
    public void insertFile(List<DiskFiles> files) {
        insertFile(files, rowids -> {
            //noop
        });
    }

    /**
     * Asynchronously inserts a list of disk file records into the datastore, allows tracking result
     * via provided callback
     *
     * @param files list of room entities to insert
     * @param callback callback to retrive primary keys on successful insert
     */
    public void insertFile(List<DiskFiles> files, DatastoreInsertUpdateCallback<List<Long>> callback) {
        executor.execute(() -> {
           List<Long> ids = mDatastore.diskFilesDao().insertAll(files);
           callback.onRowUpdate(ids);
        });
    }

    /**
     * gets a randomized list of messages from the datastore. Needs to be observed
     * to get async result
     *
     * @param count how many messages to retrieve
     * @return livedata representation of list of messages
     */
    public LiveData<List<ScatterMessage>> getTopRandomMessages(int count) {
        return this.mDatastore.scatterMessageDao().getTopRandom(count);
    }

    /**
     * gets a list of all the files in the datastore.
     * @return list of DiskFiles objects
     */
    public LiveData<List<DiskFiles>> getAllFiles() {
        return this.mDatastore.diskFilesDao().getAllAsync();
    }

    /**
     * Retrieves a message by an identity room entity
     *
     * @param id room entity to search by
     * @return livedata representation of list of messages
     */
    public LiveData<List<ScatterMessage>> getMessagesByIdentity(Identity id) {
        return this.mDatastore.scatterMessageDao().getByIdentity(id.getIdentityID());
    }

    /**
     * Clears the datastore, dropping all tables
     */
    public void clear() {
        this.mDatastore.clearAllTables();
    }

    public static class DatastoreInsertException extends Exception {
        public DatastoreInsertException() {
            super();
        }
    }

    public interface DatastoreInsertUpdateCallback<T> {
        void onRowUpdate(T rowids);
    }
}
