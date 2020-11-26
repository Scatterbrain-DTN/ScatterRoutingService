package com.example.uscatterbrain.db.file;

import android.util.Log;

import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.google.protobuf.ByteString;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.Single;

public class FileStoreImpl implements FileStore{
    private static final FileStoreImpl mFileStoreInstance = null;
    private final Map<Path, OpenFile> mOpenFiles;

    @Inject
    public FileStoreImpl() {
        mOpenFiles = new ConcurrentHashMap<>();
    }

    public Single<FileCallbackResult> deleteFile(Path path) {
        return Single.fromCallable(() -> {
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

    public Single<FileCallbackResult> insertFile(InputStream is, Path path) {
        return Single.fromCallable(() -> {
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
    }

    public Single<FileCallbackResult> insertFile(BlockHeaderPacket header, InputStream inputStream, int count, Path path) {

        return BlockSequencePacket.parseFrom(inputStream)
                .repeat(count)
                .toObservable()
                .concatMap(blockSequencePacket -> {
                    if (!blockSequencePacket.verifyHash(header)) {
                        return Observable.error(new IllegalStateException("failed to verify hash"));
                    }
                    return insertFile(blockSequencePacket.getmData(), path, WriteMode.APPEND).toObservable();
                })
                .reduce((fileCallbackResult, fileCallbackResult2) -> {
                    if (fileCallbackResult == FileCallbackResult.ERR_SUCCESS && fileCallbackResult2 == FileCallbackResult.ERR_SUCCESS) {
                        return FileCallbackResult.ERR_SUCCESS;
                    } else  {
                        return FileCallbackResult.ERR_FAILED;
                    }
                }).toSingle();
     }

    public Single<FileCallbackResult> insertFile(ByteString data, Path path, WriteMode mode) {
        return Single.fromCallable(() -> {
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
    }

    public Single<FileCallbackResult> getFile(OutputStream os, Path path) {
        return Single.fromCallable(() -> {
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
                return FileStore.FileCallbackResult.ERR_IO_EXCEPTION;
            }
            return FileStore.FileCallbackResult.ERR_SUCCESS;
        });
    }

    public Single<List<ByteString>> hashFile(Path path, int blocksize) {
        return Single.fromCallable(() -> {
            List<ByteString> r = new ArrayList<>();
            if (!path.toFile().exists()) {
                return null;
            }

            FileInputStream is = new FileInputStream(path.toFile());
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
        });
    }
}
