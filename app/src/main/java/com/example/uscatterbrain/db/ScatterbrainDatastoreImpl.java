package com.example.uscatterbrain.db;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.example.uscatterbrain.ScatterbrainDatastore;
import com.example.uscatterbrain.db.entities.Hashes;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.IdentityRelations;
import com.example.uscatterbrain.db.entities.Keys;
import com.example.uscatterbrain.db.entities.MessageHashCrossRef;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.ScatterDataPacket;
import com.google.protobuf.ByteString;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import javax.inject.Inject;

/**
 * Interface to the androidx room backed datastore
 * used for storing messages, identities, and other metadata.
 */
public class ScatterbrainDatastoreImpl implements ScatterbrainDatastore {

    private Datastore mDatastore;
    private Executor executor;
    private Context ctx;
    /**
     * constructor
     * @param ctx  application or service context
     */
    @Inject
    public ScatterbrainDatastoreImpl(
            Context ctx,
            Datastore datastore
    ) {
        mDatastore = datastore;
        executor = Executors.newSingleThreadExecutor();
        this.ctx = ctx;
    }

    /**
     *  For internal use, synchronously inserts messages to database
     * @param message room entity for message to insert
     * @return primary keys of message inserted
     */
    @Override
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
    @Override
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
    @Override
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
    @Override
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
    @Override
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
    @Override
    public LiveData<List<ScatterMessage>> getTopRandomMessages(int count) {
        return this.mDatastore.scatterMessageDao().getTopRandom(count);
    }

    /**
     * gets a list of all the files in the datastore.
     * @return list of DiskFiles objects
     */
    @Override
    public LiveData<List<String>> getAllFiles() {
        return this.mDatastore.scatterMessageDao().getAllFiles();
    }

    /**
     * Retrieves a message by an identity room entity
     *
     * @param id room entity to search by
     * @return livedata representation of list of messages
     */
    @Override
    public LiveData<List<ScatterMessage>> getMessagesByIdentity(Identity id) {
        return this.mDatastore.scatterMessageDao().getByIdentity(id.getIdentityID());
    }

    @Override
    public List<FutureTask<ScatterDataPacketInsertResult<Long>>> insertDataPacket(List<ScatterDataPacket> packets) {
        List<FutureTask<ScatterDataPacketInsertResult<Long>>> finalResult = new ArrayList<>();
        for (ScatterDataPacket dataPacket : packets) {
            FutureTask<ScatterDataPacketInsertResult<Long>> result = insertDataPacket(dataPacket);
        }
        return finalResult;
     }

     @Override
     public FutureTask<ScatterDataPacketInsertResult<List<Long>>> insertIdentity(List<com.example.uscatterbrain.identity.Identity> identity) {
        FutureTask<ScatterDataPacketInsertResult<List<Long>>> result = new FutureTask<>(() -> {
            List<Identity> idlist = new ArrayList<>();
            List<Keys> keysList = new ArrayList<>();
            for (com.example.uscatterbrain.identity.Identity identity1 : identity) {
                Identity id = new Identity();
                id.setGivenName(identity1.getName());
                id.setPublicKey(identity1.getPubkey());
                id.setSignature(identity1.getSig());

                idlist.add(id);
            }
            List<Long> identityID = mDatastore.identityDao().insertAll(idlist);
            if(identityID.size() != identity.size()) {
                return new ScatterDataPacketInsertResult<>(null, DatastoreSuccessCode.DATASTORE_SUCCESS_CODE_FAILURE);
            }

            for (int i=0;i<identity.size();i++) {
                com.example.uscatterbrain.identity.Identity identity1 = identity.get(i);
                for (Map.Entry<String, ByteString> entry : identity1.getKeymap().entrySet()) {
                    Keys k = new Keys();
                    k.setKey(entry.getKey());
                    k.setValue(entry.getValue().toByteArray());
                    k.setIdentityFK(identityID.get(i));
                    keysList.add(k);
                }
            }
            mDatastore.identityDao().insertKeys(keysList);

            return new ScatterDataPacketInsertResult<List<Long>>(identityID, DatastoreSuccessCode.DATASTORE_SUCCESS_CODE_SUCCESS);
        });

        executor.execute(result);
        return result;
     }

     @Override
     public FutureTask<ScatterDataPacketInsertResult<Long>> insertDataPacket(ScatterDataPacket packet) {
        FutureTask<ScatterDataPacketInsertResult<Long>> result = new FutureTask<>(() -> {
            if(!packet.isHashValid()) {
                return new ScatterDataPacketInsertResult<Long>(-1L,DatastoreSuccessCode.DATASTORE_SUCCESS_CODE_FAILURE);
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

            return new ScatterDataPacketInsertResult<Long>(id, DatastoreSuccessCode.DATASTORE_SUCCESS_CODE_SUCCESS);
        });

        executor.execute(result);
        return result;
     }

     public FutureTask<List<com.example.uscatterbrain.identity.Identity>> getIdentity(List<Long> ids) {
        FutureTask<List<com.example.uscatterbrain.identity.Identity>> result = new FutureTask<>(() -> {
            List<com.example.uscatterbrain.identity.Identity> r = new ArrayList<>();
            try {
                List<IdentityRelations> idlist = mDatastore.identityDao().getIdentitiesWithRelations(ids);
                for (IdentityRelations relation : idlist) {
                    Map<String, ByteString> keylist = new HashMap<>(relation.keys.size());
                    for (Keys keys : relation.keys) {
                        keylist.put(keys.getKey(), ByteString.copyFrom(keys.getValue()));
                    }
                    com.example.uscatterbrain.identity.Identity identity = com.example.uscatterbrain.identity.Identity.newBuilder(ctx)
                            .setName(relation.identity.getGivenName())
                            .setScatterbrainPubkey(ByteString.copyFrom(relation.identity.getPublicKey()))
                            .setSig(relation.identity.getSignature())
                            .build();

                    identity.putAll(keylist);
                    r.add(identity);

                }
            } catch (Exception e) {
                e.printStackTrace();
                return new ArrayList<>();
            }
            return r;
        });

        executor.execute(result);
        return result;
     }

     @Override
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
    @Override
    public void clear() {
        this.mDatastore.clearAllTables();
    }
}
