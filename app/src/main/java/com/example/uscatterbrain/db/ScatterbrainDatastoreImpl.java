package com.example.uscatterbrain.db;

import android.content.Context;
import android.net.Uri;
import android.os.FileObserver;
import android.provider.DocumentsContract.Document;
import android.util.Log;
import android.util.Pair;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import com.example.uscatterbrain.RoutingServiceBackend;
import com.example.uscatterbrain.RoutingServiceComponent;
import com.example.uscatterbrain.db.entities.HashlessScatterMessage;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.Keys;
import com.example.uscatterbrain.db.entities.MessageHashCrossRef;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;


/**
 * Interface to the androidx room backed datastore
 * used for storing messages, identities, and other metadata.
 */
@Singleton
public class ScatterbrainDatastoreImpl implements ScatterbrainDatastore {

    private static final String TAG = "ScatterbrainDatastore";
    public static int DEFAULT_BLOCKSIZE = 1024*8;
    private final Datastore mDatastore;
    private final Context ctx;
    private final Scheduler databaseScheduler;
    private final ConcurrentHashMap<Path, ScatterbrainDatastore.OpenFile> mOpenFiles;
    private final File USER_FILES_DIR;
    private final File CACHE_FILES_DIR;
    private final FileObserver userDirectoryObserver;
    /**
     * constructor
     * @param ctx  application or service context
     */
    @Inject
    public ScatterbrainDatastoreImpl(
            Context ctx,
            Datastore datastore,
            @Named(RoutingServiceComponent.NamedSchedulers.DATABASE) Scheduler databaseScheduler
    ) {
        mDatastore = datastore;
        this.ctx = ctx;
        this.databaseScheduler = databaseScheduler;

        mOpenFiles = new ConcurrentHashMap<>();
        USER_FILES_DIR =  new File(ctx.getFilesDir(), USER_FILES_PATH);
        CACHE_FILES_DIR =  new File(ctx.getFilesDir(), CACHE_FILES_PATH);
        getUserDir(); //create user and cahce directories so we can monitor them
        getCacheDir();
        userDirectoryObserver = new FileObserver(USER_FILES_DIR) {
            @Override
            public void onEvent(int i, @Nullable String s) {
                switch (i) {
                    case FileObserver.CLOSE_WRITE:
                    {
                        if (s != null) {
                            Log.v(TAG, "file closed in user directory; " + s);
                            final File f = new File(USER_FILES_DIR, s);
                            if (f.exists() && f.length() > 0) {
                                insertAndHashLocalFile(f, DEFAULT_BLOCKSIZE);
                            } else if (f.length() == 0) {
                                Log.e(TAG, "file length was zero, not hashing");
                            } else {
                                Log.e(TAG, "closed file does not exist, race condition??!");
                            }
                        }
                        break;
                    }
                    case FileObserver.OPEN:
                    {
                        if (s != null) {
                            Log.v(TAG, "file created in user directory: " + s);
                        }
                        break;
                    }
                    case FileObserver.DELETE:
                    {
                        if (s != null) {
                            Log.v(TAG, "file deleted in user directory: " + s);
                        }
                        break;
                    }
                }
            }
        };
        userDirectoryObserver.startWatching();
    }

    private Completable insertMessageWithoutIdentity(ScatterMessage message, Long identityid) {
        return this.mDatastore.scatterMessageDao().insertHashes(message.messageHashes)
                .flatMap(hashids -> {
                    message.message.identityID = identityid;
                    return mDatastore.scatterMessageDao()._insertMessages(message.message)
                            .flatMap(messageid -> {
                                List<MessageHashCrossRef> hashes = new ArrayList<>();
                                for (Long hashID : hashids) {
                                    MessageHashCrossRef xref = new MessageHashCrossRef();
                                    xref.messageID = messageid;
                                    xref.hashID = hashID;
                                    hashes.add(xref);
                                }
                                return this.mDatastore.scatterMessageDao().insertMessagesWithHashes(hashes);
                            });
                }).ignoreElement();
    }

    /**
     *  For internal use, synchronously inserts messages to database
     * @param message room entity for message to insert
     * @return primary keys of message inserted
     */
    @Override
    public Completable insertMessagesSync(ScatterMessage message) {
        if (message.message.identity != null) {
            return this.mDatastore.scatterMessageDao().insertIdentity(message.message.identity)
                    .flatMapCompletable(identityid -> insertMessageWithoutIdentity(message, identityid));
        } else {
            return insertMessageWithoutIdentity(message, null);
        }
    }

    /**
     * For internal use, synchronously inserts messages into the database
     * @param messages list of room entities to insert
     * @return list of primary keys for rows inserted
     */
    private Completable insertMessagesSync(List<ScatterMessage> messages) {
        return Observable.fromIterable(messages)
                .flatMap(scatterMessage -> insertMessagesSync(scatterMessage).toObservable())
                .ignoreElements();
    }

    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param messages room entities to insert
     * @return future returning list of ids inserted
     */
    @Override
    public Completable insertMessages(List<ScatterMessage> messages) {
        return insertMessagesSync(messages);
    }

    /**
     * Asynchronously inserts a single message into the datastore, allows tracking result
     * via provided callback
     *
     * @param message room entity to insert
     * @return future returning id of row inserted
     */
    @Override
    public Completable insertMessage(ScatterMessage message) {
        return insertMessagesSync(message);
    }

    @Override
    public Completable insertMessage(WifiDirectRadioModule.BlockDataStream stream) {
        File filePath = getFilePath(stream.getHeaderPacket());
        Log.e(TAG, "inserting message at filePath " + filePath);
        stream.getEntity().message.filePath = filePath.getAbsolutePath();
        return mDatastore.scatterMessageDao().messageCountSingle(filePath.getAbsolutePath())
                .flatMapCompletable(count -> {
                    if (count > 0) {
                        //TODO: we read and discard packets here because currently, but eventually
                        // it would be a good idea to check the hash first and add support for aborting the transfer
                        return stream.getSequencePackets()
                                .ignoreElements();
                    } else {
                        return insertMessage(stream.getEntity())
                                .andThen(insertFile(stream));
                    }
                });
    }

    /**
     * Asynchronously inserts a list of identities into the datastore, allows tracking result
     * via provided callback
     *
     * @param identities list of room entities to insert
     * @return future returning list of row ids inserted
     */
    @Override
    public Completable insertIdentity(Identity[] identities) {
        return mDatastore.identityDao().insertAll(identities).ignoreElement();
    }

    /**
     * Asynchronously inserts an identity into the datastore, allows tracking result
     * via provided callback
     *
     * @param identity room entity to insert
     * @return future returning row id inserted
     */
    @Override
    public Completable insertIdentity(Identity identity) {
        return mDatastore.identityDao().insertAll(identity).ignoreElement();
    }

    /**
     * gets a randomized list of messages from the datastore. Needs to be observed
     * to get async result
     *
     * @param count how many messages to retrieve
     * @return livedata representation of list of messages
     */
    @Override
    public Observable<WifiDirectRadioModule.BlockDataStream> getTopRandomMessages(int count) {
        Log.v(TAG, "called getTopRandomMessages");
        return this.mDatastore.scatterMessageDao().getTopRandom(count)
                .doOnSubscribe(disp -> Log.v(TAG, "subscribed to getTopRandoMessages"))
                .doOnNext(message -> Log.v(TAG, "retrieved message"))
                .map(scatterMessage -> new WifiDirectRadioModule.BlockDataStream(
                        scatterMessage,
                        readFile(new File(scatterMessage.message.filePath), scatterMessage.message.blocksize)
                        ));
    }

    /**
     * gets a list of all the files in the datastore.
     * @return list of DiskFiles objects
     */
    @Override
    public Observable<String> getAllFiles() {
        return this.mDatastore.scatterMessageDao().getAllFiles()
                .toObservable()
                .flatMap(Observable::fromIterable);
    }

    /**
     * Retrieves a message by an identity room entity
     *
     * @param id room entity to search by
     * @return livedata representation of list of messages
     */
    @Override
    public Observable<ScatterMessage> getMessagesByIdentity(Identity id) {
        return this.mDatastore.scatterMessageDao().getByIdentity(id.getIdentityID())
                .toObservable()
                .flatMap(Observable::fromIterable);
    }

    @Override
    public Single<ScatterMessage> getMessageByPath(String path) {
        return this.mDatastore.scatterMessageDao().getByFilePath(path)
                .toObservable()
                .flatMap(Observable::fromIterable)
                .firstOrError();
    }


    public ScatterMessage getMessageByPathSync(String path) {
        //note: this has unique constraint so it is safe, I am terribly sorry for this horrible code
        final List<ScatterMessage> result = this.mDatastore.scatterMessageDao().getByFilePath(path).blockingGet();
        if (result.size() == 0) {
            return null;
        } else {
            return result.get(0);
        }
    }

    @Override
    public Completable insertIdentity(List<com.example.uscatterbrain.identity.Identity> identity) {
        return Observable.fromCallable(() -> {
            List<Identity> idlist = new ArrayList<>();
            List<Keys> keysList = new ArrayList<>();
            for (com.example.uscatterbrain.identity.Identity identity1 : identity) {
                Identity id = new Identity();
                id.setGivenName(identity1.getName());
                id.setPublicKey(identity1.getPubkey());
                id.setSignature(identity1.getSig());

                idlist.add(id);
            }
            return mDatastore.identityDao().insertAll(idlist)
                    .flatMap(identityidlist -> {
                        if(identityidlist.size() != identity.size()) {
                            return Single.error(new IllegalStateException("identity list sizes do not match"));
                        }

                        for (int i=0;i<identity.size();i++) {
                            com.example.uscatterbrain.identity.Identity identity1 = identity.get(i);
                            for (Map.Entry<String, ByteString> entry : identity1.getKeymap().entrySet()) {
                                Keys k = new Keys();
                                k.setKey(entry.getKey());
                                k.setValue(entry.getValue().toByteArray());
                                k.setIdentityFK(identityidlist.get(i));
                                keysList.add(k);
                            }
                        }
                        return mDatastore.identityDao().insertKeys(keysList);
                    });

        }).ignoreElements();
     }

     public Observable<com.example.uscatterbrain.identity.Identity> getIdentity(List<Long> ids) {
            return mDatastore.identityDao().getIdentitiesWithRelations(ids)
                    .toObservable()
                        .flatMap(idlist -> {
                            return Observable.fromIterable(idlist)
                                    .map(relation -> {
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
                                        return identity;
                                    });
                        });
    }

    @Override
    public Map<String, Serializable> getFileMetadataSync(File path) {
        return getMessageByPath(path.getAbsolutePath())
                .map(message -> {
                    final HashMap<String, Serializable> result = new HashMap<>();
                    result.put(Document.COLUMN_DOCUMENT_ID, message.message.filePath);
                    result.put(Document.COLUMN_MIME_TYPE, message.message.mimeType);
                    if (message.message.userFilename != null) {
                        result.put(Document.COLUMN_DISPLAY_NAME, message.message.userFilename);
                    } else {
                        result.put(Document.COLUMN_DISPLAY_NAME, ScatterbrainDatastore.getDefaultFileNameFromHashes(message.messageHashes));
                    }
                    result.put(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE); //TODO: is this enough?
                    result.put(Document.COLUMN_SIZE, getFileSize(path));
                    result.put(Document.COLUMN_SUMMARY, "shared via scatterbrain");
                    return result;
                })
                .onErrorReturn(err -> new HashMap<>())
                .blockingGet();
    }

    @Override
    public synchronized Map<String, Serializable> insertAndHashLocalFile(File path, int blocksize) {
        return hashFile(path, blocksize)
                .flatMapCompletable(hashes -> {
                    Log.e(TAG, "hashing local file, len:" + hashes.size());
                    HashlessScatterMessage message = new HashlessScatterMessage();
                    message.to = null;
                    message.from = null;
                    message.application = ByteString.copyFromUtf8(
                            RoutingServiceBackend.Applications.APPLICATION_FILESHARING
                    ).toByteArray();
                    message.sig = null;
                    message.sessionid = 0;
                    message.blocksize = blocksize;
                    message.userFilename = path.getName();
                    message.extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(path).toString());
                    message.filePath = path.getAbsolutePath();
                    message.mimeType = ScatterbrainDatastore.getMimeType(path);
                    ScatterMessage hashedMessage = new ScatterMessage();
                    hashedMessage.message = message;
                    hashedMessage.messageHashes = HashlessScatterMessage.hash2hashs(hashes);
                    return this.insertMessage(hashedMessage);
                }).toSingleDefault(getFileMetadataSync(path))
                .blockingGet();
    }

    @Override
    public synchronized int deleteByPath(File path) {
        return mDatastore.scatterMessageDao().deleteByPath(path.getAbsolutePath());
    }

    @Override
    public int messageCount() {
        return mDatastore.scatterMessageDao().messageCount();
    }

    /**
     * Clears the datastore, dropping all tables
     */
    @Override
    public void clear() {
        this.mDatastore.clearAllTables();
    }



    @Override
    public Completable deleteFile(File path) {
        return Single.fromCallable(() -> {
            if (!path.exists()) {
                return FileCallbackResult.ERR_FILE_NO_EXISTS;
            }

            if (!close(path)) {
                return FileCallbackResult.ERR_FAILED;
            }

            if(path.delete()) {
                return FileCallbackResult.ERR_SUCCESS;
            } else {
                return FileCallbackResult.ERR_FAILED;
            }
        }).flatMapCompletable(result -> {
            if (result.equals(FileCallbackResult.ERR_SUCCESS)) {
                return Completable.complete();
            } else {
                return Completable.error(new IllegalStateException(result.toString()));
            }
        });
    }

    @Override
    public boolean isOpen(File path) {
        return mOpenFiles.containsKey(path.toPath());
    }

    @Override
    public boolean close(File path) {
        if (isOpen(path)) {
            OpenFile f = mOpenFiles.get(path);
            if (f != null) {
                try {
                    f.close();
                } catch (IOException e) {
                    return false;
                }
                mOpenFiles.remove(path);
            }
        }
        return  true;
    }

    @Override
    public File getCacheDir() {
        if (!CACHE_FILES_DIR.exists()) {
            if (!CACHE_FILES_DIR.mkdirs()) {
                return null;
            }
        }
        return CACHE_FILES_DIR;
    }

    @Override
    public File getUserDir() {
        if (!USER_FILES_DIR.exists()) {
            if (!USER_FILES_DIR.mkdirs()) {
                return null;
            }
        }
        return USER_FILES_DIR;
    }

    @Override
    public File getFilePath(BlockHeaderPacket packet) {
        return new File(getCacheDir(), packet.getAutogenFilename());
    }

    @Override
    public long getFileSize(File path) {
        return path.length();
    }

    @Override
    public Single<OpenFile> open(File path) {
        return Single.fromCallable(() -> {
            OpenFile old = mOpenFiles.get(path.toPath());
            if (old == null) {
                OpenFile f = new OpenFile(path, false);
                mOpenFiles.put(path.toPath(), f);
                return f;
            } else {
                return old;
            }
        });

    }

    private Completable insertSequence(Flowable<BlockSequencePacket> packets, BlockHeaderPacket header, File path) {
        return Single.fromCallable(() -> new FileOutputStream(path))
                .flatMapCompletable(fileOutputStream -> packets
                        .concatMapCompletable(blockSequencePacket -> {
                            if (!blockSequencePacket.verifyHash(header)) {
                                return Completable.error(new IllegalStateException("failed to verify hash"));
                            }
                            return Completable.fromAction(() -> blockSequencePacket.getmData().writeTo(fileOutputStream))
                                    .subscribeOn(Schedulers.io());
                        }));
    }

    @Override
    public Completable insertFile(WifiDirectRadioModule.BlockDataStream stream) {
        final File file = getFilePath(stream.getHeaderPacket());
        Log.v(TAG, "insertFile: " + file);

        return Completable.fromAction(() -> {
            if (!file.createNewFile()) {
                throw new FileAlreadyExistsException("file " + file + " already exists");
            }
        }).andThen(insertSequence(
                stream.getSequencePackets(),
                stream.getHeaderPacket(),
                file
        ));
    }

    @Override
    public Single<List<ByteString>> hashFile(File path, int blocksize) {
        return Single.fromCallable(() -> {
            List<ByteString> r = new ArrayList<>();
            if (!path.exists()) {
                throw new FileAlreadyExistsException("file already exists");
            }

            FileInputStream is = new FileInputStream(path);
            byte[] buf = new byte[blocksize];
            int read;
            int seqnum = 0;

            while((read = is.read(buf)) != -1){
                BlockSequencePacket blockSequencePacket = BlockSequencePacket.newBuilder()
                        .setSequenceNumber(seqnum)
                        .setData(ByteString.copyFrom(buf, 0, read))
                        .build();
                r.add(blockSequencePacket.calculateHashByteString());
                seqnum++;
                Log.e("debug", "hashing "+ read);
            }
            return r;
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Flowable<BlockSequencePacket> readFile(File path, int blocksize) {
        Log.v(TAG, "called readFile " + path);
        return Flowable.fromCallable(() -> new FileInputStream(path))
                .doOnSubscribe(disp -> Log.v(TAG, "subscribed to readFile"))
                .flatMap(is -> {
                    Flowable<Integer> seq = Flowable.generate(() -> 0, (state, emitter) -> {
                        emitter.onNext(state);
                        return state + 1;
                    });
                    return Flowable.just(is)
                            .zipWith(seq, (fileInputStream, seqnum) -> {
                                return Flowable.fromCallable(() -> {
                                    byte[] buf = new byte[blocksize];
                                    int read;

                                    read = is.read(buf);
                                    return new Pair<>(read, buf);
                                })
                                        .takeWhile(pair -> pair.first != -1)
                                        .map(pair -> {
                                            Log.e("debug", "reading "+ pair.first);
                                            return BlockSequencePacket.newBuilder()
                                                    .setSequenceNumber(seqnum)
                                                    .setData(ByteString.copyFrom(pair.second, 0, pair.first))
                                                    .build();
                                        })
                                        .subscribeOn(Schedulers.io());
                            }).concatMap(result -> result);
                }).doOnComplete(() -> Log.v(TAG, "readfile completed"));
    }
}
