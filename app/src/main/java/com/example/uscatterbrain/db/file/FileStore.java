package com.example.uscatterbrain.db.file;

import android.util.Log;

import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public class FileStore {
    private static FileStore mFileStoreInstance = null;
    private Executor mWriteExecutor;
    private Map<Path, OpenFile> mOpenFiles;

    private FileStore() {
        mWriteExecutor = Executors.newSingleThreadExecutor();
        mOpenFiles = new HashMap<>();
    }

    public static FileStore getFileStore() {
        if (mFileStoreInstance == null) {
            mFileStoreInstance = new FileStore();
        }
        return mFileStoreInstance;
    }

    public FutureTask<FileCallbackResult> deleteFile(Path path) {
        FutureTask<FileCallbackResult> result = new FutureTask<>(() -> {
            if (!path.toFile().exists()) {
                return FileCallbackResult.ERR_FILE_NO_EXISTS;
            }

            if (!close(path)) {
                return FileCallbackResult.ERR_FAILED;
            }

            if(path.toFile().delete()) {
                return FileCallbackResult.ERR_SUCCESS;
            } else {
                return FileCallbackResult.ERR_FAILED;
            }
        });

        mWriteExecutor.execute(result);
        return result;
    }

    public boolean isOpen(Path path) {
        return mOpenFiles.containsKey(path);
    }

    public boolean close(Path path) {
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

    public boolean open(Path path) {
        if (!isOpen(path)) {
            try {
                OpenFile f = new OpenFile(path, false);
                mOpenFiles.put(path, f);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public FutureTask<FileCallbackResult> insertFile(InputStream is, Path path) {
        FutureTask<FileCallbackResult> result = new FutureTask<>(() -> {
            if (path.toFile().exists()) {
                return FileCallbackResult.ERR_FILE_EXISTS;
            }

            if (!open(path)) {
                return FileCallbackResult.ERR_IO_EXCEPTION;
            }

            try {
                OpenFile f = mOpenFiles.get(path);
                if (f != null) {
                    FileOutputStream os = f.getOutputStream();
                    byte[] buf = new byte[8 * 1024];
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        os.write(buf, 0, read);
                    }
                    f.getOutputStream().close();
                    f.lock();
                } else {
                    return FileCallbackResult.ERR_FILE_NO_EXISTS;
                }
            } catch (IOException e) {
                return FileCallbackResult.ERR_IO_EXCEPTION;
            }
            return FileCallbackResult.ERR_SUCCESS;
        });

        mWriteExecutor.execute(result);
        return result;
    }

    public FutureTask<FileCallbackResult> insertFile(ByteString data, Path path, WriteMode mode) {
        return insertFile(data, path, mode, null);
    }

    public FutureTask<FileCallbackResult> insertFile(BlockHeaderPacket header, InputStream inputStream, int count, Path path) {
        FutureTask<FileCallbackResult> await = null;

        for (int i=0;i<count;i++) {
            Log.e("debug", "writing file "+ i);
            BlockSequencePacket blockSequencePacket = BlockSequencePacket.parseFrom(inputStream);
            if (blockSequencePacket == null) {
                await =  new FutureTask<>(() -> FileCallbackResult.ERR_FAILED);
                Log.e("debug", "blocksequence was null");
                mWriteExecutor.execute(await);
                return await;
            }

            if (!blockSequencePacket.verifyHash(header)) {
                await = new FutureTask<>(() -> FileCallbackResult.ERR_FAILED);
                Log.e("debug", "failed to verify blocksequence hash");
                mWriteExecutor.execute(await);
                return await;
            }

            await = insertFile(blockSequencePacket.getmData(), path, WriteMode.APPEND, await);
        }
        if (await == null) {
            await = new FutureTask<>(() -> FileCallbackResult.ERR_FAILED);

            Log.e("debug", "await was null");
            mWriteExecutor.execute(await);
            return await;
        } else {
            return await;
        }
     }

    public FutureTask<FileCallbackResult> insertFile(ByteString data, Path path, WriteMode mode, FutureTask<FileCallbackResult> await) {
        FutureTask<FileCallbackResult> result = new FutureTask<>(() -> {
            if (await != null) {
                if (await.get() != FileCallbackResult.ERR_SUCCESS) {
                    return FileCallbackResult.ERR_FAILED;
                }
            }
            if (!open(path)) {
                return FileCallbackResult.ERR_IO_EXCEPTION;
            }

            try {
                OpenFile f = mOpenFiles.get(path);
                if (f == null) {
                    return FileCallbackResult.ERR_FILE_NO_EXISTS;
                }
                switch (mode) {
                    case APPEND:
                        f.setMode(true);
                        break;
                    case OVERWRITE:
                        f.setMode(false);
                        break;
                    default:
                        return FileCallbackResult.ERR_INVALID_ARGUMENT;
                }

                FileOutputStream os = f.getOutputStream();
                data.writeTo(os);

            } catch (IOException e) {
                return FileCallbackResult.ERR_IO_EXCEPTION;

            }
            return FileCallbackResult.ERR_SUCCESS;
        });

        mWriteExecutor.execute(result);
        return result;
    }

    public FutureTask<FileCallbackResult> getFile(OutputStream os, Path path) {
        FutureTask<FileCallbackResult> result = new FutureTask<>(() -> {
            try {
                if (!path.toFile().exists()) {
                    return FileCallbackResult.ERR_FILE_NO_EXISTS;
                }

                FileInputStream is = new FileInputStream(path.toFile());

                byte[] buf = new byte[8*1024];
                int read;
                while ((read = is.read(buf)) != -1) {
                    os.write(buf, 0, read);
                }

                is.close();
                os.close();
            } catch (IOException e) {
                return FileCallbackResult.ERR_IO_EXCEPTION;
            }
            return FileCallbackResult.ERR_SUCCESS;
        });

        mWriteExecutor.execute(result);
        return result;
    }

    public FutureTask<List<ByteString>> hashFile(Path path, int blocksize) {
        FutureTask<List<ByteString>> result = new FutureTask<>(() -> {
            List<ByteString> r = new ArrayList<>();
            if (!path.toFile().exists()) {
                return null;
            }

            FileInputStream is = new FileInputStream(path.toFile());
            byte[] hash = new byte[GenericHash.BYTES];
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
            }
            return r;
        });
        mWriteExecutor.execute(result);
        return result;
    }

    public enum FileCallbackResult {
        ERR_FILE_EXISTS,
        ERR_FILE_NO_EXISTS,
        ERR_IO_EXCEPTION,
        ERR_PERMISSION_DENIED,
        ERR_FAILED,
        ERR_SUCCESS,
        ERR_INVALID_ARGUMENT
    }

    public enum WriteMode {
        APPEND,
        OVERWRITE
    }

    public interface FileStoreCallback<T> extends Runnable {
        void setResult(T result);
    }

    public static class OpenFile implements Closeable {
        private FileInputStream mIs;
        private FileOutputStream mOs;
        private File mFile;
        private WriteMode mMode;
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
