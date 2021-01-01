package com.example.uscatterbrain.db.file;

import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.google.protobuf.ByteString;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public interface FileStore {
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
