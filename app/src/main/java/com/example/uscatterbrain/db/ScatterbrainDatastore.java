package com.example.uscatterbrain.db;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.room.Room;

import com.example.uscatterbrain.db.entities.Hashes;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.MessageHashCrossRef;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.ScatterDataPacket;
import com.google.protobuf.ByteString;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

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
     *  For internal use, synchronously inserts messages to database
     * @param message room entity for message to insert
     * @return primary keys of message inserted
     */
    public Long insertMessagesSync(ScatterMessage message) {
        Long id = this.mDatastore.scatterMessageDao()._insertMessages(message);

        List<MessageHashCrossRef> hashes = new ArrayList<>();

        List<Long> hashids = this.mDatastore.scatterMessageDao().insertHashes(message.getHashes());
        for (Long hashID : hashids) {
            MessageHashCrossRef xref = new MessageHashCrossRef();
            xref.messageID = id;
            xref.hashID = hashID;
            hashes.add(xref);
        }

        Long identityID = this.mDatastore.scatterMessageDao().insertIdentity(message.getIdentity());
        message.setIdentityID(identityID);

        this.mDatastore.scatterMessageDao().insertMessagesWithHashes(hashes);
        return id;
    }

    /**
     * For internal use, synchronously inserts messages into the database
     * @param messages list of room entities to insert
     * @return list of primary keys for rows inserted
     */
    private List<Long> insertMessagesSync(List<ScatterMessage> messages) {
        List<Long> ids =  this.mDatastore.scatterMessageDao()._insertMessages(messages);

        List<MessageHashCrossRef> hashes = new ArrayList<>();

        Iterator<Long> idItr = ids.iterator();
        Iterator<ScatterMessage> messageItr = messages.iterator();
        while (idItr.hasNext() && messageItr.hasNext()) {
            ScatterMessage message = messageItr.next();
            Long id = idItr.next();
            List<Long> hashids = this.mDatastore.scatterMessageDao().insertHashes(message.getHashes());
            for (Long hashID : hashids) {
                MessageHashCrossRef xref = new MessageHashCrossRef();
                xref.messageID = id;
                xref.hashID = hashID;
                hashes.add(xref);
            }

            Long identityID = this.mDatastore.scatterMessageDao().insertIdentity(message.getIdentity());
            message.setIdentityID(identityID);
        }
        this.mDatastore.scatterMessageDao().insertMessagesWithHashes(hashes);

        return ids;
    }


    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param messages room entities to insert
     * @throws DatastoreInsertException
     * @return future returning list of ids inserted
     */
    public FutureTask<List<Long>> insertMessages(List<ScatterMessage> messages) throws DatastoreInsertException {
        for(ScatterMessage message : messages) {
            if (message.getIdentity() == null || message.getHashes() == null) {
                throw new DatastoreInsertException();
            }
        }

        FutureTask<List<Long>> result = new FutureTask<>(() -> {
            return insertMessagesSync(messages);
        });
        executor.execute(result);
        return result;
    }


    /**
     * Asynchronously inserts a single message into the datastore, allows tracking result
     * via provided callback
     *
     * @param message room entity to insert
     * @throws DatastoreInsertException thrown if inner classes are null
     * @return future returning id of row inserted
     */
    public FutureTask<Long> insertMessage(ScatterMessage message) throws DatastoreInsertException {
        if(message.getIdentity() == null || message.getHashes() == null) {
            throw new DatastoreInsertException();
        }

        FutureTask<Long> result = new FutureTask<>(() -> insertMessagesSync(message));

        executor.execute(result);
        return result;
    }

    /**
     * Asynchronously inserts a list of identities into the datastore, allows tracking result
     * via provided callback
     *
     * @param identities list of room entities to insert
     * @return future returning list of row ids inserted
     */
    public FutureTask<List<Long>> insertIdentity(Identity[] identities) {
        FutureTask<List<Long>> result = new FutureTask<>(() -> mDatastore.identityDao().insertAll(identities));
        executor.execute(result);
        return result;
    }

    /**
     * Asynchronously inserts an identity into the datastore, allows tracking result
     * via provided callback
     *
     * @param identity room entity to insert
     * @return future returning row id inserted
     */
    public FutureTask<Long> insertIdentity(Identity identity) {
        FutureTask<Long> result = new FutureTask<>(() -> {
            List<Long> ids = mDatastore.identityDao().insertAll(identity);
            return ids.get(0);
        });
        executor.execute(result);
        return result;
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
    public LiveData<List<String>> getAllFiles() {
        return this.mDatastore.scatterMessageDao().getAllFiles();
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

    public List<FutureTask<ScatterDataPacketInsertResult>> insertDataPacket(List<ScatterDataPacket> packets) {
        List<FutureTask<ScatterDataPacketInsertResult>> finalResult = new ArrayList<>();
        for (ScatterDataPacket dataPacket : packets) {
            FutureTask<ScatterDataPacketInsertResult> result = insertDataPacket(dataPacket);
        }
        return finalResult;
     }

     public FutureTask<ScatterDataPacketInsertResult> insertDataPacket(ScatterDataPacket packet) {
        FutureTask<ScatterDataPacketInsertResult> result = new FutureTask<>(() -> {
            if(!packet.isHashValid()) {
                return ScatterDataPacketInsertResult.newFailure();
            }

            ScatterMessage message = new ScatterMessage();
            message.setFilePath(packet.getFile().getAbsolutePath());
            message.setBody(new byte[0]); //TODO: implement body
            message.setFrom(packet.getHeader().getFromFingerprint().toByteArray());
            message.setTo(packet.getHeader().getToFingerprint().toByteArray());
            message.setSig(packet.getHeader().getSig().toByteArray());
            message.setApplication(packet.getHeader().getApplication());
            message.setSessionid(packet.getHeader().getSessionID());
            message.setIdentity(null); //TODO: update identity later
            Log.e("debug", "header blocksize " + packet.getHeader().getBlockSize());
            message.setBlocksize(packet.getHeader().getBlockSize());

            List<Hashes> hashlist = new ArrayList<>();
            for (ByteString hash : packet.getHashes()) {
                Hashes hashes = new Hashes();
                hashes.setHash(hash.toByteArray());
                hashlist.add(hashes);
            }

            message.setHashes(hashlist);
            Long id = mDatastore.scatterMessageDao()._insertMessages(message);
            List<Long> ids = mDatastore.scatterMessageDao().insertHashes(hashlist);
            List<MessageHashCrossRef> xrefs = new ArrayList<>();
            for (Long hashid : ids) {
                MessageHashCrossRef crossRef = new MessageHashCrossRef();
                crossRef.messageID = id;
                crossRef.hashID = hashid;
            }

            mDatastore.scatterMessageDao().insertMessagesWithHashes(xrefs);

            return ScatterDataPacketInsertResult.newScatterDataPacketInsertResult(id);
        });

        executor.execute(result);
        return result;
     }

     public FutureTask<List<ScatterDataPacket>> getDataPacket(List<Long> id) {
        FutureTask<List<ScatterDataPacket>> result = new FutureTask<>(() -> {
            List<ScatterMessage> messages = mDatastore.scatterMessageDao().getByIDSync(id);
            List<ScatterDataPacket> bdlist = new ArrayList<>();
            for (ScatterMessage message : messages) {
                File f = Paths.get(message.getFilePath()).toAbsolutePath().toFile();
                ScatterDataPacket dataPacket = ScatterDataPacket.newBuilder()
                        .setApplication(ByteString.copyFrom(message.getApplication()).toStringUtf8())
                        .setFromAddress(ByteString.copyFrom(message.getFrom()))
                        .setToAddress(ByteString.copyFrom(message.getTo()))
                        .setSessionID(message.getSessionid())
                        .setBlockSize(message.getBlocksize())
                        .setFragmentFile(f)
                        .setSig(ByteString.copyFrom(message.getSig()))
                        .build();

                bdlist.add(dataPacket);
            }

            return bdlist;
        });

        executor.execute(result);
        return result;
     }

    /**
     * Clears the datastore, dropping all tables
     */
    public void clear() {
        this.mDatastore.clearAllTables();
    }

    public enum DatastoreSuccessCode {
        DATASTORE_SUCCESS_CODE_SUCCESS,
        DATASTORE_SUCCESS_CODE_FAILURE
    }

    public static class ScatterDataPacketInsertResult {
        private Long scatterMessageId;
        private DatastoreSuccessCode successCode;

        private ScatterDataPacketInsertResult(Long messageid, DatastoreSuccessCode code) {
            this.scatterMessageId = messageid;
            this.successCode = code;
        }

        public static ScatterDataPacketInsertResult newScatterDataPacketInsertResult(Long scatterMessageId) {
            return new ScatterDataPacketInsertResult(scatterMessageId, DatastoreSuccessCode.DATASTORE_SUCCESS_CODE_SUCCESS);
        }

        public static ScatterDataPacketInsertResult newFailure() {
            return new ScatterDataPacketInsertResult(null, DatastoreSuccessCode.DATASTORE_SUCCESS_CODE_FAILURE);
        }

        public Long getScatterMessageId() {
            return scatterMessageId;
        }

        public DatastoreSuccessCode getSuccessCode() {
            return successCode;
        }
    }

    public static class DatastoreInsertException extends Exception {
        public DatastoreInsertException() {
            super();
        }
    }
}
