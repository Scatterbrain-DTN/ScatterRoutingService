package net.ballmerlabs.uscatterbrain.db;

import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import net.ballmerlabs.scatterbrainsdk.Identity;
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity;
import net.ballmerlabs.uscatterbrain.db.entities.Hashes;
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage;
import net.ballmerlabs.uscatterbrain.db.entities.KeylessIdentity;
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage;
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket;
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket;
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket;
import net.ballmerlabs.uscatterbrain.network.IdentityPacket;
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;

public interface ScatterbrainDatastore {

    String DATABASE_NAME = "scatterdb";
    int DEFAULT_BLOCKSIZE = 1024*2;
    final Pattern FILE_SANITIZE = Pattern.compile("/^[\\w.-]+$/\n");

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
    Completable insertMessageToRoom(ScatterMessage message);


    /**
     * gets a randomized list of messages from the datastore. Needs to be observed
     * to get async result
     *
     * @param count how many messages to retrieve
     * @return livedata representation of list of messages
     */
    Observable<WifiDirectRadioModule.BlockDataStream> getTopRandomMessages(
            int count,
            final DeclareHashesPacket delareHashes
    );


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
    Observable<ScatterMessage> getMessagesByIdentity(KeylessIdentity id);

    Completable insertIdentityPacket(List<IdentityPacket> identity);

    Observable<IdentityPacket> getIdentity(List<Long> ids);

    Map<String, Serializable> getFileMetadataSync(File path);

    Map<String, Serializable> insertAndHashLocalFile(File path, int blocksize);

    Single<ScatterMessage> getMessageByPath(String path);

    Completable insertApiIdentity(ApiIdentity identity);

    Completable insertApiIdentities(List<Identity> identities);

    ApiIdentity getApiIdentityByFingerprint(String fingerprint);

    Completable addACLs(String identityFingerprint, String packagename, String appsig);

    Completable deleteACLs(String identityFingerprint, String packageName, String appsig);

    Maybe<ApiIdentity.KeyPair> getIdentityKey(String identity);

    int messageCount();

    int deleteByPath(File path);

    void clear();

    String USER_FILES_PATH = "userFiles";
    String CACHE_FILES_PATH = "systemFiles";

    Completable deleteFile(File path);

    boolean isOpen(File path);

    boolean close(File path);

    Single<OpenFile> open(File path);

    Completable insertFile(WifiDirectRadioModule.BlockDataStream stream);

    Single<List<ByteString>> hashFile(File path, int blocksize);

    Flowable<BlockSequencePacket> readFile(File path, int blocksize);

    Flowable<BlockSequencePacket> readBody(byte[] body, int blocksize);

    File getFilePath(BlockHeaderPacket packet);

    File getCacheDir();

    File getUserDir();

    long getFileSize(File path);

    List<Identity> getAllIdentities();

    List<net.ballmerlabs.scatterbrainsdk.ScatterMessage> getApiMessages(String application);

    Flowable<IdentityPacket> getTopRandomIdentities(int count);

    net.ballmerlabs.scatterbrainsdk.ScatterMessage getApiMessages(long id);

    Completable insertAndHashFileFromApi(ApiScatterMessage message, int blocksize);

    Single<DeclareHashesPacket> getDeclareHashesPacket();

    Single<List<ACL>> getACLs(String identity);

    enum FileCallbackResult {
        ERR_FILE_EXISTS,
        ERR_FILE_NO_EXISTS,
        ERR_IO_EXCEPTION,
        ERR_PERMISSION_DENIED,
        ERR_FAILED,
        ERR_SUCCESS,
        ERR_INVALID_ARGUMENT
    }

    enum WriteMode {
        APPEND,
        OVERWRITE
    }

    static String getDefaultFileNameFromHashes(List<Hashes> hashes) {
        return getDefaultFileName(HashlessScatterMessage.hashes2hash(hashes));
    }

    static String sanitizeFilename(String name) {
        return FILE_SANITIZE.matcher(name).replaceAll("-");
    }

    class ACL {
        public final String packageName;
        public final String appsig;

        public ACL(String packageName, String appsig) {
            this.packageName = packageName;
            this.appsig = appsig;
        }
    }

    static String getNoFilename(byte[] body) {
        byte[] outhash = new byte[GenericHash.BYTES];
        byte[] state = new byte[LibsodiumInterface.getSodium().crypto_generichash_statebytes()];
        LibsodiumInterface.getSodium().crypto_generichash_init(state, null, 0, outhash.length);
        LibsodiumInterface.getSodium().crypto_generichash_update(state, body, body.length);
        LibsodiumInterface.getSodium().crypto_generichash_final(state, outhash, outhash.length);
        ByteBuffer buf = ByteBuffer.wrap(outhash);
        //note: this only is safe because crypto_generichash_BYTES_MIN is 16
        return new UUID(buf.getLong(), buf.getLong()).toString();
    }

    static byte[] getGlobalHash(List<ByteString> hashes) {
        byte[] outhash = new byte[GenericHash.BYTES];
        byte[] state = new byte[LibsodiumInterface.getSodium().crypto_generichash_statebytes()];
        LibsodiumInterface.getSodium().crypto_generichash_init(state, null, 0, outhash.length);
        for (ByteString bytes : hashes) {
            LibsodiumInterface.getSodium().crypto_generichash_update(state, bytes.toByteArray(), bytes.size());
        }
        LibsodiumInterface.getSodium().crypto_generichash_final(state, outhash, outhash.length);
        return outhash;
    }

    static byte[] getGlobalHashDb(List<Hashes> hashes) {
        byte[] outhash = new byte[GenericHash.BYTES];
        byte[] state = new byte[LibsodiumInterface.getSodium().crypto_generichash_statebytes()];
        LibsodiumInterface.getSodium().crypto_generichash_init(state, null, 0, outhash.length);
        for (Hashes bytes : hashes) {
            LibsodiumInterface.getSodium().crypto_generichash_update(state, bytes.hash, bytes.hash.length);
        }
        LibsodiumInterface.getSodium().crypto_generichash_final(state, outhash, outhash.length);
        return outhash;
    }


    static String getDefaultFileName(List<ByteString> hashes) {
        ByteBuffer buf = ByteBuffer.wrap(getGlobalHash(hashes));
        //note: this only is safe because crypto_generichash_BYTES_MIN is 16
        return new UUID(buf.getLong(), buf.getLong()).toString();
    }

    static String getDefaultFileName(BlockHeaderPacket packet) {
        return getDefaultFileName(packet.getHashList());
    }

    class OpenFile implements Closeable {
        private final FileInputStream mIs;
        private FileOutputStream mOs;
        private final File mFile;
        private final WriteMode mMode;
        private boolean mLocked;

        public OpenFile(File path, boolean append) throws IOException {
            this.mMode = WriteMode.OVERWRITE;
            this.mFile = path;
            this.mOs = new FileOutputStream(mFile,append);
            this.mIs = new FileInputStream(mFile);
            this.mLocked = false;
        }

        public void lock() {
            mLocked = true;
        }

        public void unlock() {
            mLocked = false;
        }

        @Override
        public void close() throws IOException {
            mIs.close();
            mOs.close();
        }

        public boolean setMode(boolean append) {
            try {
                this.mOs.close();
                this.mOs = new FileOutputStream(mFile, append);
            } catch (IOException e) {
                return false;
            }
            return true;
        }

        private boolean reset() {
            if (mMode == WriteMode.APPEND) {
                return setMode(true);
            } else {
                return setMode(false);
            }
        }

        public FileOutputStream getOutputStream() {
            if(mLocked) {
                return null;
            } else {
                return mOs;
            }
        }

        public FileInputStream getInputStream() {
            return mIs;
        }
    }

}
