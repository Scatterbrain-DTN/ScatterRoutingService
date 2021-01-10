package com.example.uscatterbrain.db.file;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class FileStoreImpl implements FileStore {
    private static final String TAG = "FileStore";
    private final ConcurrentHashMap<Path, OpenFile> mOpenFiles;
    private final Context mCtx;
    private final File USER_FILES_DIR;
    private final File CACHE_FILES_DIR;

    @Inject
    public FileStoreImpl(
            Context context
    ) {
        mOpenFiles = new ConcurrentHashMap<>();
        this.mCtx = context;
        USER_FILES_DIR =  new File(mCtx.getFilesDir(), USER_FILES_PATH);
        CACHE_FILES_DIR =  new File(mCtx.getFilesDir(), CACHE_FILES_PATH);
    }

    @Override
    public Completable deleteFile(Path path) {
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
        }).flatMapCompletable(result -> {
            if (result.equals(FileCallbackResult.ERR_SUCCESS)) {
                return Completable.complete();
            } else {
                return Completable.error(new IllegalStateException(result.toString()));
            }
        });
    }

    @Override
    public boolean isOpen(Path path) {
        return mOpenFiles.containsKey(path);
    }

    @Override
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
        return new File(CACHE_FILES_DIR, packet.getAutogenFilename());
    }

    @Override
    public File getFilePath(ScatterMessage message) {
        return new File(CACHE_FILES_DIR, message.filePath);
    }

    @Override
    public long getFileSize(Path path) {
        return path.toFile().length();
    }

    @Override
    public Single<OpenFile> open(Path path) {
        return Single.fromCallable(() -> {
            OpenFile old = mOpenFiles.get(path);
            if (old == null) {
                OpenFile f = new OpenFile(path, false);
                mOpenFiles.put(path, f);
                return f;
            } else {
                return old;
            }
        });

    }

    @Override
    public Completable insertFile(InputStream is, Path path) {
        if (path.toFile().exists()) {
            return Completable.error(new FileAlreadyExistsException("file already exists"));
        }

        return open(path)
                .flatMapCompletable(f -> Completable.fromAction(() -> {
                    FileOutputStream os = f.getOutputStream();
                    byte[] buf = new byte[8 * 1024];
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        os.write(buf, 0, read);
                    }
                    f.getOutputStream().close();
                    f.lock();
                }).subscribeOn(Schedulers.io()));
    }

    private Completable insertSequence(Flowable<BlockSequencePacket> packets, BlockHeaderPacket header, Path path) {
        return packets
          .concatMapCompletable(blockSequencePacket -> {
            if (!blockSequencePacket.verifyHash(header)) {
                return Completable.error(new IllegalStateException("failed to verify hash"));
            }
            return insertFile(blockSequencePacket.getmData(), path, WriteMode.APPEND);
        });
    }

    @Override
    public Completable insertFile(BlockHeaderPacket header, InputStream inputStream, int count, Path path) {
        return insertSequence(
                BlockSequencePacket.parseFrom(inputStream)
                .repeat(count),
                header,
                path
        );
     }

    @Override
    public Completable insertFile(WifiDirectRadioModule.BlockDataStream stream) {
        return insertSequence(
                stream.getSequencePackets(),
                stream.getHeaderPacket(),
                getFilePath(stream.getHeaderPacket()).toPath()
        );
    }

    @Override
     public Completable insertFile(ByteString data, Path path, WriteMode mode) {
        return open(path)
                .flatMapCompletable(f -> Completable.fromAction(() -> {
                    switch (mode) {
                        case APPEND:
                            f.setMode(true);
                            break;
                        case OVERWRITE:
                            f.setMode(false);
                            break;
                    }
                    FileOutputStream os = f.getOutputStream();
                    data.writeTo(os);
                }).subscribeOn(Schedulers.io()));
    }

    @Override
    public Single<List<ByteString>> hashFile(Path path, int blocksize) {
        return Single.fromCallable(() -> {
            List<ByteString> r = new ArrayList<>();
            if (!path.toFile().exists()) {
                throw new FileAlreadyExistsException("file already exists");
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
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Flowable<BlockSequencePacket> readFile(Path path, int blocksize) {
        Log.v(TAG, "called readFile " + path);
        return Flowable.fromCallable(() -> new FileInputStream(path.toFile()))
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
