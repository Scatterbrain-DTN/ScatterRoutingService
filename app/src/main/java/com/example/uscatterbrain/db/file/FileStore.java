package com.example.uscatterbrain.db.file;

import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import com.example.uscatterbrain.db.entities.Hashes;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;

public interface FileStore {
    String USER_FILES_PATH = "userFiles";
    String CACHE_FILES_PATH = "systemFiles";

    Completable deleteFile(Path path);

    boolean isOpen(Path path);

    boolean close(Path path);

    Single<OpenFile> open(Path path);

    Completable insertFile(InputStream is, Path path);

    Completable insertFile(BlockHeaderPacket header, InputStream inputStream, int count, Path path);

    Completable insertFile(WifiDirectRadioModule.BlockDataStream stream);

    Completable insertFile(ByteString data, Path path, WriteMode mode);

    Single<List<ByteString>> hashFile(Path path, int blocksize);

    Flowable<BlockSequencePacket> readFile(Path path, int blocksize);

    File getFilePath(BlockHeaderPacket packet);

    File getFilePath(ScatterMessage message);

    File getCacheDir();

    File getUserDir();

    long getFileSize(Path path);

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
        return getDefaultFileName(ScatterMessage.hashes2hash(hashes));
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

        public OpenFile(Path path, boolean append) throws IOException {
            this.mMode = WriteMode.OVERWRITE;
            this.mFile = path.toFile();
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
