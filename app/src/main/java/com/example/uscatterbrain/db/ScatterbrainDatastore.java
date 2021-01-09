package com.example.uscatterbrain.db;

import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;

public interface ScatterbrainDatastore {

    String DATABASE_NAME = "scatterdb";

    /**
     *  For internal use, synchronously inserts messages to database
     * @param message room entity for message to insert
     * @return primary keys of message inserted
     */
    Completable insertMessagesSync(ScatterMessage message);


    /**
     * Inserts a message stream (with header and blocksequence packets) from a network source
     * into both the database and filestore if applicable
     * @return completable for message insertion
     */
    Completable insertMessage(WifiDirectRadioModule.BlockDataStream stream);


    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param messages room entities to insert
     * @return future returning list of ids inserted
     */
    Completable insertMessages(List<ScatterMessage> messages);


    /**
     * Asynchronously inserts a single message into the datastore, allows tracking result
     * via provided callback
     *
     * @param message room entity to insert
     * @return future returning id of row inserted
     */
    Completable insertMessage(ScatterMessage message);


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
    Observable<WifiDirectRadioModule.BlockDataStream> getTopRandomMessages(int count);


    /**
     * gets a list of all the files in the datastore.
     * @return list of DiskFiles objects
     */
    Observable<String> getAllFiles();

    /**
     * Retrieves a message by an identity room entity
     *
     * @param id room entity to search by
     * @return livedata representation of list of messages
     */
    Observable<ScatterMessage> getMessagesByIdentity(Identity id);

    Completable insertIdentity(List<com.example.uscatterbrain.identity.Identity> identity);

    Observable<com.example.uscatterbrain.identity.Identity> getIdentity(List<Long> ids);

    Map<String, Serializable> getFileMetadataSync(File path);

    Map<String, Serializable> insertAndHashLocalFile(File path, int blocksize);

    Single<ScatterMessage> getMessageByPath(String path);

    int deleteByPath(File path);

    void clear();
}
