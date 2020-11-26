package com.example.uscatterbrain.db.file;

import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.google.protobuf.ByteString;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import io.reactivex.Single;

public interface FileStore {
    Single<FileCallbackResult> deleteFile(Path path);

    boolean isOpen(Path path);

    boolean close(Path path);

    boolean open(Path path);

    Single<FileCallbackResult> insertFile(InputStream is, Path path);

    Single<FileCallbackResult> insertFile(BlockHeaderPacket header, InputStream inputStream, int count, Path path);

    Single<FileCallbackResult> insertFile(ByteString data, Path path, WriteMode mode);

    Single<List<ByteString>> hashFile(Path path, int blocksize);

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

    interface FileStoreCallback<T> extends Runnable {
        void setResult(T result);
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
