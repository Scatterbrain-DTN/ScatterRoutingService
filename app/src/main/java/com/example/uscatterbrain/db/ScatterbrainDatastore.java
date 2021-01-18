package com.example.uscatterbrain.db;

import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import com.example.uscatterbrain.db.entities.Hashes;
import com.example.uscatterbrain.db.entities.HashlessScatterMessage;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

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
import java.util.concurrent.Flow;

import io.reactivex.Completable;
import io.reactivex.Flowable;
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
    Completable insertMessageToRoom(ScatterMessage message);


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

    static String getDefaultFileName(List<ByteString> hashes) {
        byte[] outhash = new byte[GenericHash.BYTES];
        byte[] state = new byte[LibsodiumInterface.getSodium().crypto_generichash_statebytes()];
        LibsodiumInterface.getSodium().crypto_generichash_init(state, null, 0, outhash.length);
        for (ByteString bytes : hashes) {
            LibsodiumInterface.getSodium().crypto_generichash_update(state, bytes.toByteArray(), bytes.size());
        }
        LibsodiumInterface.getSodium().crypto_generichash_final(state, outhash, outhash.length);
        ByteBuffer buf = ByteBuffer.wrap(outhash);
        //note: this only is safe because crypto_generichash_BYTES_MIN is 16
        return new UUID(buf.getLong(), buf.getLong()).toString();
    }

    static String getDefaultFileName(BlockHeaderPacket packet) {
        return getDefaultFileName(packet.getHashList());
    }

    static String getMimeType(File file) {
        if (file.isDirectory()) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        } else {
            final String name = file.getName();
            final int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                final String extension = name.substring(lastDot + 1).toLowerCase();
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mime != null) return mime;
            }
            return "application/octet-stream";
        }
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
