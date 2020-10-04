package com.example.uscatterbrain.db;

import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockDataObservableSource;

import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Maybe;

public interface ScatterbrainDatastore {

    String DATABASE_NAME = "scatterdb";

    /**
     *  For internal use, synchronously inserts messages to database
     * @param message room entity for message to insert
     * @return primary keys of message inserted
     */
    Completable insertMessagesSync(ScatterMessage message);


    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param messages room entities to insert
     * @throws ScatterbrainDatastoreImpl.DatastoreInsertException
     * @return future returning list of ids inserted
     */
    Completable insertMessages(List<ScatterMessage> messages) throws ScatterbrainDatastoreImpl.DatastoreInsertException;


    /**
     * Asynchronously inserts a single message into the datastore, allows tracking result
     * via provided callback
     *
     * @param message room entity to insert
     * @throws ScatterbrainDatastoreImpl.DatastoreInsertException thrown if inner classes are null
     * @return future returning id of row inserted
     */
    Completable insertMessage(ScatterMessage message) throws ScatterbrainDatastoreImpl.DatastoreInsertException;


    /**
     * Asynchronously inserts a list of identities into the datastore, allows tracking result
     * via provided callback
     *
     * @param identities list of room entities to insert
     * @return future returning list of row ids inserted
     */
    Completable insertIdentity(Identity[] identities);

    /**
     * Asynchronously inserts an identity into the datastore, allows tracking result
     * via provided callback
     *
     * @param identity room entity to insert
     * @return future returning row id inserted
     */
    Completable insertIdentity(Identity identity);



    /**
     * gets a randomized list of messages from the datastore. Needs to be observed
     * to get async result
     *
     * @param count how many messages to retrieve
     * @return livedata representation of list of messages
     */
    Maybe<List<ScatterMessage>> getTopRandomMessages(int count);


    /**
     * gets a list of all the files in the datastore.
     * @return list of DiskFiles objects
     */
    Maybe<List<String>> getAllFiles();

    /**
     * Retrieves a message by an identity room entity
     *
     * @param id room entity to search by
     * @return livedata representation of list of messages
     */
    Maybe<List<ScatterMessage>> getMessagesByIdentity(Identity id);

    Completable insertDataPacket(List<BlockDataObservableSource> packets);

    Completable insertIdentity(List<com.example.uscatterbrain.identity.Identity> identity);

    Completable insertDataPacket(BlockDataObservableSource packet);

    Maybe<List<com.example.uscatterbrain.identity.Identity>> getIdentity(List<Long> ids);

    Maybe<List<BlockDataObservableSource>> getDataPacket(List<Long> id);

    void clear();

    class ScatterDataPacketInsertResult<T> {
        private T scatterMessageId;
        private ScatterbrainDatastoreImpl.DatastoreSuccessCode successCode;

        public ScatterDataPacketInsertResult(T messageid, ScatterbrainDatastoreImpl.DatastoreSuccessCode code) {
            this.scatterMessageId = messageid;
            this.successCode = code;
        }

        public T getScatterMessageId() {
            return scatterMessageId;
        }

        public ScatterbrainDatastoreImpl.DatastoreSuccessCode getSuccessCode() {
            return successCode;
        }
    }

    class DatastoreInsertException extends Exception {
        public DatastoreInsertException() {
            super();
        }
    }

    enum DatastoreSuccessCode {
        DATASTORE_SUCCESS_CODE_SUCCESS,
        DATASTORE_SUCCESS_CODE_FAILURE
    }
}
