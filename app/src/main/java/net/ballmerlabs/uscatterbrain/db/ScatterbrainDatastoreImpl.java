package net.ballmerlabs.uscatterbrain.db;

import android.content.Context;
import android.net.Uri;
import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.util.Log;
import android.util.Pair;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi;
import net.ballmerlabs.uscatterbrain.R;
import net.ballmerlabs.uscatterbrain.RouterPreferences;
import net.ballmerlabs.uscatterbrain.RoutingServiceBackend;
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent;
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity;
import net.ballmerlabs.uscatterbrain.db.entities.ClientApp;
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage;
import net.ballmerlabs.uscatterbrain.db.entities.Identity;
import net.ballmerlabs.uscatterbrain.db.entities.KeylessIdentity;
import net.ballmerlabs.uscatterbrain.db.entities.Keys;
import net.ballmerlabs.uscatterbrain.db.entities.MessageHashCrossRef;
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage;
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket;
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket;
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket;
import net.ballmerlabs.uscatterbrain.network.IdentityPacket;
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;


/**
 * Interface to the androidx room backed datastore
 * used for storing messages, identities, and other metadata.
 */
@Singleton
public class ScatterbrainDatastoreImpl implements ScatterbrainDatastore {

    private static final String TAG = "ScatterbrainDatastore";
    private final Datastore mDatastore;
    private final Context ctx;
    private final Scheduler databaseScheduler;
    private final ConcurrentHashMap<Path, ScatterbrainDatastore.OpenFile> mOpenFiles;
    private final File USER_FILES_DIR;
    private final File CACHE_FILES_DIR;
    private final FileObserver userDirectoryObserver;
    private final RouterPreferences preferences;
    /**
     * constructor
     * @param ctx  application or service context
     */
    @Inject
    public ScatterbrainDatastoreImpl(
            Context ctx,
            Datastore datastore,
            @Named(RoutingServiceComponent.NamedSchedulers.DATABASE) Scheduler databaseScheduler,
            RouterPreferences preferences
    ) {
        mDatastore = datastore;
        this.ctx = ctx;
        this.databaseScheduler = databaseScheduler;
        this.preferences = preferences;

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
                                insertAndHashLocalFile(f, ScatterbrainDatastore.DEFAULT_BLOCKSIZE);
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

    @Override
    public Completable insertMessagesSync(ScatterMessage message) {
        return this.mDatastore.scatterMessageDao().insertHashes(message.messageHashes)
                .subscribeOn(databaseScheduler)
                .flatMap(hashids -> {
                    message.message.globalhash = ScatterbrainDatastore.getGlobalHashDb(message.messageHashes);
                    return mDatastore.scatterMessageDao()._insertMessages(message.message)
                            .subscribeOn(databaseScheduler)
                            .flatMap(messageid -> {
                                List<MessageHashCrossRef> hashes = new ArrayList<>();
                                for (Long hashID : hashids) {
                                    MessageHashCrossRef xref = new MessageHashCrossRef();
                                    xref.messageID = messageid;
                                    xref.hashID = hashID;
                                    hashes.add(xref);
                                }
                                return this.mDatastore.scatterMessageDao().insertMessagesWithHashes(hashes)
                                        .subscribeOn(databaseScheduler);
                            });
                }).ignoreElement();
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
    public Completable insertMessageToRoom(ScatterMessage message) {
        return insertMessagesSync(message);
    }

    private Completable discardStream(WifiDirectRadioModule.BlockDataStream stream) {
        //TODO: we read and discard packets here because currently, but eventually
        // it would be a good idea to check the hash first and add support for aborting the transfer
        return stream.getSequencePackets()
                .map(packet -> {
                    if (packet.verifyHash(stream.getHeaderPacket())) {
                        Log.v(TAG, "hash verified");
                        return packet;
                    } else  {
                        Log.e(TAG, "hash invalid");
                        return null;
                    }
                }).ignoreElements();
    }

    @Override
    public Completable insertMessage(WifiDirectRadioModule.BlockDataStream stream) {
        if (stream.getToDisk()) {
            return insertMessageWithDisk(stream);
        } else {
            return insertMessagesWithoutDisk(stream);
        }
    }

    private Completable insertMessageWithDisk(WifiDirectRadioModule.BlockDataStream stream) {
        File filePath = getFilePath(stream.getHeaderPacket());
        Log.e(TAG, "inserting message at filePath " + filePath);
        stream.getEntity().message.filePath = filePath.getAbsolutePath();
        return mDatastore.scatterMessageDao().messageCountSingle(filePath.getAbsolutePath())
                .flatMapCompletable(count -> {
                    if (count > 0) {
                        return discardStream(stream);
                    } else {
                        return insertMessageToRoom(stream.getEntity())
                                .andThen(insertFile(stream));
                    }
                }).subscribeOn(databaseScheduler);
    }

    private Completable insertMessagesWithoutDisk(WifiDirectRadioModule.BlockDataStream stream) {
        return mDatastore.scatterMessageDao().messageCountSingle(stream.getHeaderPacket().getAutogenFilename())
                .flatMapCompletable(count -> {
                    if (count > 0) {
                        return discardStream(stream);
                    } else {
                        return stream.getSequencePackets()
                                .flatMap(packet -> {
                                    if (packet.verifyHash(stream.getHeaderPacket())) {
                                        return Flowable.just(packet.getmData());
                                    } else {
                                        Log.e(TAG, "invalid hash");
                                        return Flowable.error(new SecurityException("failed to verify hash"));
                                    }
                                })
                                .reduce(ByteString::concat)
                                .flatMapCompletable(val -> {
                                    stream.getEntity().message.body = val.toByteArray();
                                    return insertMessageToRoom(stream.getEntity());
                                }).subscribeOn(databaseScheduler);

                    }
                });
    }

    @Override
    public Flowable<BlockSequencePacket> readBody(byte[] body, int blocksize) {
        return Bytes.from(new ByteArrayInputStream(body), blocksize)
                .zipWith(getSeq(), (bytes, seq) -> {
                   return BlockSequencePacket.newBuilder()
                           .setData(ByteString.copyFrom(bytes))
                           .setSequenceNumber(seq)
                           .build();
                });
    }

    /**
     * gets a randomized list of messages from the datastore. Needs to be observed
     * to get async result
     *
     * @param count how many messages to retrieve
     * @return livedata representation of list of messages
     */
    @Override
    public Observable<WifiDirectRadioModule.BlockDataStream> getTopRandomMessages(
            int count,
            final DeclareHashesPacket declareHashes
    ) {
        Log.v(TAG, "called getTopRandomMessages");
        final int num = Math.min(count, mDatastore.scatterMessageDao().messageCount());

        return this.mDatastore.scatterMessageDao().getTopRandomExclusingHash(count, declareHashes.getHashes())
                .doOnSubscribe(disp -> Log.v(TAG, "subscribed to getTopRandoMessages"))
                .toFlowable()
                .flatMap(Flowable::fromIterable)
                .doOnNext(message -> Log.v(TAG, "retrieved message: " + message.messageHashes.size()))
                .zipWith(getSeq(), (message, s) -> {
                    if (message.message.body == null) {
                        return new WifiDirectRadioModule.BlockDataStream(
                                message,
                                readFile(new File(message.message.filePath), message.message.blocksize),
                                s < num - 1,
                                true
                        );
                    } else {
                        return new WifiDirectRadioModule.BlockDataStream(
                                message,
                                readBody(message.message.body, message.message.blocksize),
                                s < num - 1,
                                false
                        );
                    }
                }).toObservable();
    }

    private Flowable<Integer> getSeq() {
        return Flowable.generate(() -> 0, (state, emitter) -> {
            emitter.onNext(state);
            return state + 1;
        });
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
    public Observable<ScatterMessage> getMessagesByIdentity(KeylessIdentity id) {
        return this.mDatastore.scatterMessageDao().getByIdentity(id.fingerprint)
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

    private Completable insertIdentity(Observable<Identity> identityObservable) {
       return identityObservable
        .flatMapCompletable(singleid ->
                mDatastore.identityDao().insert(singleid.identity)
                        .subscribeOn(databaseScheduler)
                        .flatMapCompletable(result -> {
                            return Observable.fromIterable(singleid.keys)
                                    .map(key -> {
                                        key.identityFK = result;
                                        return key;
                                    })
                                    .reduce(new ArrayList<Keys>(), (list, key) -> {
                                        list.add(key);
                                        return list;
                                    })
                                    .flatMapCompletable(l -> mDatastore.identityDao().insertKeys(l)
                                            .subscribeOn(databaseScheduler)
                                            .ignoreElement()
                                    )
                                    .andThen(
                                            Observable.fromCallable(
                                                    () -> singleid.clientACL != null ? singleid.clientACL : new ArrayList<ClientApp>()
                                            )
                                            .flatMap(Observable::fromIterable)
                                            .map(acl -> {
                                                acl.identityFK = result;
                                                return acl;
                                            })
                                            .reduce(new ArrayList<ClientApp>(), (list, acl) -> {
                                                list.add(acl);
                                                return list;
                                            })
                                            .flatMapCompletable(a ->
                                                    mDatastore.identityDao().insertClientApps(a)
                                                    .subscribeOn(databaseScheduler)
                                                    .ignoreElement()
                                            )
                                    );
                        })
        );
    }

    private Completable insertIdentity(Identity... ids) {
        return Single.just(ids)
                .flatMapCompletable(identities -> insertIdentity(Observable.fromArray(identities)));
    }

    private Completable insertIdentity(List<Identity> ids) {
        return Single.just(ids)
                .flatMapCompletable(identities -> insertIdentity(Observable.fromIterable(identities)));
    }

    private String getFingerprint(net.ballmerlabs.scatterbrainsdk.Identity identity) {
        byte[] fingeprint = new byte[GenericHash.BYTES];
        LibsodiumInterface.getSodium().crypto_generichash(
                fingeprint,
                fingeprint.length,
                identity.getmScatterbrainPubKey(),
                identity.getmScatterbrainPubKey().length,
                null,
                0
        );

        return LibsodiumInterface.base64enc(fingeprint);
    }

    @Override
    public Completable addACLs(String identityFingerprint, String packagename, String appsig) {
        return mDatastore.identityDao().getIdentityByFingerprint(identityFingerprint)
                .flatMapCompletable(identity -> {
                    ClientApp app = new ClientApp();
                    app.identityFK = identity.identity.identityID;
                    app.packageName = packagename;
                    app.packageSignature = appsig;
                    return mDatastore.identityDao().insertClientApp(app);
                });
    }

    @Override
    public Completable deleteACLs(String identityFingerprint, String packageName, String appsig) {
        return mDatastore.identityDao().getIdentityByFingerprint(identityFingerprint)
                .flatMapCompletable(identity -> {
                    ClientApp app = new ClientApp();
                    app.identityFK = identity.identity.identityID;
                    app.packageName = packageName;
                    app.packageSignature = appsig;
                    return mDatastore.identityDao().deleteClientApps(app);
                });
    }

    @Override
    public Completable insertApiIdentity(ApiIdentity ids) {
        return Single.just(ids)
                .map(identity -> {
                    final Identity id = new Identity();
                    final KeylessIdentity kid = new KeylessIdentity();
                    kid.fingerprint = getFingerprint(identity);
                    kid.givenName = identity.getGivenname();
                    kid.publicKey = identity.getmScatterbrainPubKey();
                    kid.signature = identity.getSig();
                    kid.privatekey = identity.getPrivateKey();
                    id.keys = keys2keysBytes(identity.getmPubKeymap());
                    id.identity = kid;
                    return id;
                }).flatMapCompletable(this::insertIdentity);
    }

    @Override
    public Completable insertApiIdentities(List<net.ballmerlabs.scatterbrainsdk.Identity> identities) {
        return Observable.fromIterable(identities)
                .map(identity -> {
                    final Identity id = new Identity();
                    final KeylessIdentity kid = new KeylessIdentity();
                    kid.fingerprint = getFingerprint(identity);
                    kid.givenName = identity.getGivenname();
                    kid.publicKey = identity.getmScatterbrainPubKey();
                    kid.signature = identity.getSig();
                    id.keys = keys2keysBytes(identity.getmPubKeymap());
                    id.identity = kid;
                    return id;
                }).reduce(new ArrayList<Identity>(), (list, id) -> {
                    list.add(id);
                    return list;
                }).flatMapCompletable(this::insertIdentity);
    }

    private List<Keys> keys2keysBytes(Map<String, byte[]> k) {
        final List<Keys> res = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : k.entrySet()) {
            final Keys keys = new Keys();
            keys.value = e.getValue();
            keys.key = e.getKey();
            res.add(keys);
        }
        return res;
    }

    private List<Keys> keys2keys(Map<String, ByteString> k) {
        final List<Keys> res = new ArrayList<>();
        for (Map.Entry<String, ByteString> e : k.entrySet()) {
            final Keys keys = new Keys();
            keys.value = e.getValue().toByteArray();
            keys.key = e.getKey();
            res.add(keys);
        }
        return res;
    }

    private Map<String, byte[]> keys2map(List<Keys> keys) {
        final HashMap<String, byte[]> res = new HashMap<>();
        for (Keys k : keys) {
            res.put(k.key, k.value);
        }
        return res;
    }

    @Override
    public Completable insertIdentityPacket(List<IdentityPacket> ids) {
        return Observable.fromIterable(ids)
                .flatMap(identity -> {
                    if (identity.isEnd() || identity.isEmpty()) {
                        return Observable.never();
                    }
                    final KeylessIdentity id = new KeylessIdentity();
                    final Identity finalIdentity = new Identity();
                    if (!identity.verifyed25519(identity.getPubkey())) {
                        Log.e(TAG, "identity " + identity.getName() + " " + identity.getFingerprint() + " failed sig check");
                        return Observable.never();
                    }
                    id.givenName = identity.getName();
                    id.publicKey = identity.getPubkey();
                    id.signature = identity.getSig();
                    id.fingerprint = identity.getFingerprint();
                    finalIdentity.identity = id;
                    finalIdentity.keys = keys2keys(identity.getKeymap());
                    return Observable.just(finalIdentity);
                })
                .reduce(new ArrayList<Identity>(), (list, identity) -> {
                    list.add(identity);
                    return list;
                })
                .flatMapCompletable(this::insertIdentity);
     }

     public Observable<IdentityPacket> getIdentity(List<Long> ids) {
            return mDatastore.identityDao().getIdentitiesWithRelations(ids)
                    .subscribeOn(databaseScheduler)
                    .toObservable()
                        .flatMap(idlist -> {
                            return Observable.fromIterable(idlist)
                                    .map(relation -> {
                                        Map<String, ByteString> keylist = new HashMap<>(relation.keys.size());
                                        for (Keys keys : relation.keys) {
                                            keylist.put(keys.key, ByteString.copyFrom(keys.value));
                                        }
                                        IdentityPacket identity = IdentityPacket.newBuilder(ctx)
                                                .setName(relation.identity.givenName)
                                                .setScatterbrainPubkey(ByteString.copyFrom(relation.identity.publicKey))
                                                .setSig(relation.identity.signature)
                                                .build();

                                        identity.putAll(keylist);
                                        return identity;
                                    });
                        });
    }

    @Override
    public Flowable<IdentityPacket> getTopRandomIdentities(int count) {
        final int num = Math.min(count, mDatastore.identityDao().getIdentityCount());
        return mDatastore.identityDao().getTopRandom(count)
                .flatMapObservable(Observable::fromIterable)
                .doOnComplete(() -> Log.v(TAG, "datastore retrieved identities"))
                .doOnNext(id -> Log.v(TAG, "retrieved single identity"))
                .toFlowable(BackpressureStrategy.BUFFER)
                .subscribeOn(databaseScheduler)
                .zipWith(getSeq(), (identity, seq) -> IdentityPacket.newBuilder(ctx)
                        .setName(identity.identity.givenName)
                        .setScatterbrainPubkey(ByteString.copyFrom(identity.identity.publicKey))
                        .setSig(identity.identity.signature)
                        .setEnd(seq < num - 1)
                        .build());
    }


    @Override
    public Single<DeclareHashesPacket> getDeclareHashesPacket() {
        return mDatastore.scatterMessageDao().getTopHashes(
                preferences.getInt(ctx.getString(R.string.pref_declarehashescap), 512)
        )
                .subscribeOn(databaseScheduler)
                .doOnSuccess(p -> Log.v(TAG, "retrieved declareHashesPacket from datastore: " + p.size()))
                .flatMapObservable(Observable::fromIterable)
                .reduce(new ArrayList<byte[]>(), (list, hash) -> {
                    list.add(hash);
                    return list;
                })
                .map(hash -> {
                    if (hash.size() == 0) {
                        return DeclareHashesPacket.newBuilder().optOut().build();
                    } else {
                        return DeclareHashesPacket.newBuilder().setHashesByte(hash).build();
                    }
                })
                .doOnSubscribe(d -> Log.e(TAG, "SUB"));
    }

    @Override
    public ApiIdentity getApiIdentityByFingerprint(String fingerprint) {
        return mDatastore.identityDao().getIdentityByFingerprint(fingerprint)
                .subscribeOn(databaseScheduler)
                .map(identity -> {
                    return ApiIdentity.newBuilder()
                            .setName(identity.identity.givenName)
                            .addKeys(keys2map(identity.keys))
                            .setSig(identity.identity.signature)
                            .build();
                }).blockingGet();
    }

    @Override
    public Maybe<ApiIdentity.KeyPair> getIdentityKey(String identity) {
        return mDatastore.identityDao().getIdentityByFingerprint(identity)
                .subscribeOn(databaseScheduler)
                .map(id -> {
                    if (id.identity.privatekey == null) {
                        throw new IllegalStateException("private key not found");
                    }
                    return new ApiIdentity.KeyPair(id.identity.publicKey, id.identity.privatekey);
                });
    }

    @Override
    public Single<List<ACL>> getACLs(String identity) {
        return mDatastore.identityDao().getClientApps(identity)
                .subscribeOn(databaseScheduler)
                .flatMapObservable(Observable::fromIterable)
                .map(clientApp -> {
                    return new ACL(
                            clientApp.packageName,
                            clientApp.packageSignature
                    );
                })
                .reduce(new ArrayList<ACL>(), (list, acl) -> {
                    list.add(acl);
                    return list;
                });
    }

    @Override
    public List<net.ballmerlabs.scatterbrainsdk.Identity> getAllIdentities() {
        return mDatastore.identityDao().getAll()
                .subscribeOn(databaseScheduler)
                .flatMapObservable(Observable::fromIterable)
                .map(identity -> ApiIdentity.newBuilder()
                        .setName(identity.identity.givenName)
                        .addKeys(keys2map(identity.keys))
                        .setSig(identity.identity.signature)
                        .build()
                ).reduce(new ArrayList<net.ballmerlabs.scatterbrainsdk.Identity>(), (list, id) -> {
                    list.add(id);
                    return list;
                }).blockingGet();
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
                    message.mimeType = ScatterbrainApi.getMimeType(path);
                    ScatterMessage hashedMessage = new ScatterMessage();
                    hashedMessage.message = message;
                    hashedMessage.messageHashes = HashlessScatterMessage.hash2hashs(hashes);
                    return this.insertMessageToRoom(hashedMessage);
                }).toSingleDefault(getFileMetadataSync(path))
                .blockingGet();
    }

    private net.ballmerlabs.scatterbrainsdk.ScatterMessage message2message(ScatterMessage message) {
        final File f = new File(message.message.filePath);
        final File r;
        if (f.exists()) {
            r = f;
        } else {
            r = null;
        }
        return net.ballmerlabs.scatterbrainsdk.ScatterMessage.newBuilder()
                .setApplication(new String(message.message.application))
                .setBody(message.message.body)
                .setFile(r, ParcelFileDescriptor.MODE_READ_ONLY)
                .setTo(message.message.to)
                .setFrom(message.message.from)
                .build();
    }

    private Single<List<net.ballmerlabs.scatterbrainsdk.ScatterMessage>> getApiMessage(Observable<ScatterMessage> entities) {
        return entities
                .map(this::message2message)
                .reduce(new ArrayList<>(), (list, m) -> {
                    list.add(m);
                    return list;
                });
    }


    private Single<net.ballmerlabs.scatterbrainsdk.ScatterMessage> getApiMessage(Single<ScatterMessage> entity) {
        return entity.map(this::message2message);
    }

    @Override
    public List<net.ballmerlabs.scatterbrainsdk.ScatterMessage> getApiMessages(String application) {
        return getApiMessage(mDatastore.scatterMessageDao()
                .getByApplication(application)
                .flatMapObservable(Observable::fromIterable))
                .blockingGet();
    }

    @Override
    public net.ballmerlabs.scatterbrainsdk.ScatterMessage getApiMessages(long id) {
        return getApiMessage(mDatastore.scatterMessageDao().getByID(id))
                .blockingGet();
    }

    @Override
    public Completable insertAndHashFileFromApi(final ApiScatterMessage message, int blocksize) {
        return Single.fromCallable(() -> File.createTempFile("scatterbrain", "insert"))
                .flatMapCompletable(file -> {
                    if (message.toDisk()) {
                        return copyFile(message.getFileDescriptor().getFileDescriptor(), file)
                                .andThen(hashFile(file, blocksize))
                                .flatMapCompletable(hashes -> {
                                    final File newFile = new File(getCacheDir(),ScatterbrainDatastore.getDefaultFileName(hashes)
                                            + ScatterbrainDatastore.sanitizeFilename(message.getExtension()));
                                    Log.v(TAG, "filepath from api: " + newFile.getAbsolutePath());
                                    if (!file.renameTo(newFile)) {
                                        return Completable.error(new IllegalStateException("failed to rename to " + newFile));
                                    }

                                    final ScatterMessage dbmessage = new ScatterMessage();
                                    dbmessage.messageHashes = HashlessScatterMessage.hash2hashs(hashes);
                                    HashlessScatterMessage hm = new HashlessScatterMessage();
                                    hm.to = message.getToFingerprint();
                                    hm.from = message.getFromFingerprint();
                                    hm.body = null;
                                    hm.blocksize = blocksize;
                                    hm.sessionid = 0;
                                    if (message.hasIdentity()) {
                                        hm.identity_fingerprint = message.getIdentityFingerprint();
                                    }
                                    if (message.signable()) {
                                        message.signEd25519(hashes);
                                        hm.sig = message.getSig();
                                    } else {
                                        hm.sig = null;
                                    }
                                    hm.userFilename = ScatterbrainDatastore.sanitizeFilename(message.getFilename());
                                    hm.extension = ScatterbrainDatastore.sanitizeFilename(message.getExtension());
                                    hm.application = ByteString.copyFromUtf8(message.getApplication()).toByteArray();
                                    hm.filePath = newFile.getAbsolutePath();
                                    hm.mimeType = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(newFile).toString());
                                    dbmessage.message = hm;
                                    return insertMessageToRoom(dbmessage);
                                }).subscribeOn(databaseScheduler);
                    } else {
                        return hashData(message.getBody(), blocksize)
                                .flatMapCompletable(hashes -> {
                                    final ScatterMessage dbmessage = new ScatterMessage();
                                    dbmessage.messageHashes = HashlessScatterMessage.hash2hashs(hashes);
                                    HashlessScatterMessage hm = new HashlessScatterMessage();
                                    hm.to = message.getToFingerprint();
                                    hm.from = message.getFromFingerprint();
                                    hm.body = message.getBody();
                                    hm.application = ByteString.copyFromUtf8(message.getApplication()).toByteArray();
                                    if (message.hasIdentity()) {
                                        hm.identity_fingerprint = message.getIdentityFingerprint();
                                    }
                                    hm.blocksize = blocksize;
                                    hm.sessionid = 0;
                                    if (message.signable()) {
                                        message.signEd25519(hashes);
                                        hm.sig = message.getSig();
                                    } else {
                                        hm.sig = null;
                                    }
                                    hm.userFilename = null;
                                    hm.extension = null;
                                    hm.filePath = ScatterbrainDatastore.getNoFilename(message.getBody());
                                    hm.mimeType = "application/octet-stream";
                                    dbmessage.message = hm;
                                    return insertMessageToRoom(dbmessage);
                                });
                    }
                });
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
                                    .subscribeOn(databaseScheduler);
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

    private Completable copyFile(FileDescriptor old, File file) {
        return Single.just(new Pair<>(old, file))
                .flatMapCompletable(pair -> {
                    if (!pair.second.createNewFile()) {
                        Log.w(TAG, "copyFile overwriting existing file");
                    }

                    if (!pair.first.valid()) {
                        return Completable.error(new IllegalStateException("invalid file descriptor: " + pair.first));
                    }

                    final FileInputStream is = new FileInputStream(pair.first);
                    final FileOutputStream os = new FileOutputStream(pair.second);
                    return Bytes.from(is)
                            .flatMapCompletable(bytes ->
                                    Completable.fromAction(() -> os.write(bytes))
                                            .subscribeOn(databaseScheduler)
                            )
                            .subscribeOn(databaseScheduler)
                            .doFinally(() -> {
                                is.close();
                                os.close();
                            });
                });
    }

    private Single<List<ByteString>> hashData(byte[] data, int blocksize) {
        return Bytes.from(new ByteArrayInputStream(data), blocksize)
                .zipWith(getSeq(), (b, seq) -> {
                    return BlockSequencePacket.newBuilder()
                            .setSequenceNumber(seq)
                            .setData(ByteString.copyFrom(b))
                            .build().getmData();
                }).reduce(new ArrayList<>(), (list, b) -> {
                    list.add(b);
                    return list;
                });
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
        }).subscribeOn(databaseScheduler);
    }

    @Override
    public Flowable<BlockSequencePacket> readFile(File path, int blocksize) {
        Log.v(TAG, "called readFile " + path);

        if (!path.exists()) {
            return Flowable.error(new FileNotFoundException(path.toString()));
        }

        return Flowable.fromCallable(() -> new FileInputStream(path))
                .doOnSubscribe(disp -> Log.v(TAG, "subscribed to readFile"))
                .flatMap(is -> {
                    return Bytes.from(path, blocksize)
                            .zipWith(getSeq(), (bytes, seqnum) -> {
                                Log.e("debug", "reading "+ bytes.length);
                                return BlockSequencePacket.newBuilder()
                                        .setSequenceNumber(seqnum)
                                        .setData(ByteString.copyFrom(bytes))
                                        .build();
                            }).subscribeOn(databaseScheduler);
                }).doOnComplete(() -> Log.v(TAG, "readfile completed"));
    }
}
