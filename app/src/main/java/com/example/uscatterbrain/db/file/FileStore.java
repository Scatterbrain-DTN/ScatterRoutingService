package com.example.uscatterbrain.db.file;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public class FileStore {
    private Executor mWriteExecutor;
    public Map<Path, AsynchronousFileChannel> mOpenFiles;

    public FileStore() {
        mWriteExecutor = Executors.newSingleThreadExecutor();
        mOpenFiles = new HashMap<>();
    }

    public FutureTask<FileCallbackResult> deleteFile(Path path) {
        FutureTask<FileCallbackResult> result = new FutureTask<>(new Callable<FileCallbackResult>() {
            @Override
            public FileCallbackResult call() throws Exception {
                if (!path.toFile().exists()) {
                    return FileCallbackResult.ERR_FILE_NO_EXISTS;
                }

                if(path.toFile().delete()) {
                    return FileCallbackResult.ERR_SUCCESS;
                } else {
                    return FileCallbackResult.ERR_FAILED;
                }
            }
        });

        mWriteExecutor.execute(result);
        return result;
    }

    public FutureTask<FileCallbackResult> insertFile(InputStream is, Path path) {
        FutureTask<FileCallbackResult> result = new FutureTask<>(new Callable<FileCallbackResult>() {
            @Override
            public FileCallbackResult call() throws Exception {
                try {
                    if (path.toFile().exists()) {
                        return FileCallbackResult.ERR_FILE_EXISTS;
                    }

                    FileOutputStream os = new FileOutputStream(path.toFile());

                    byte[] buf = new byte[8*1024];
                    int read = 0;
                    while ((read = is.read(buf)) != -1) {
                        os.write(buf, 0, read);
                    }

                    os.close();
                    is.close();

                } catch (IOException e) {
                    return FileCallbackResult.ERR_IO_EXCEPTION;
                }
                return FileCallbackResult.ERR_SUCCESS;
            }
        });

        mWriteExecutor.execute(result);
        return result;
    }

    public FutureTask<FileCallbackResult> getFile(OutputStream os, Path path) {
        FutureTask<FileCallbackResult> result = new FutureTask<>(new Callable<FileCallbackResult>() {
            @Override
            public FileCallbackResult call() throws Exception {
                try {
                    if (!path.toFile().exists()) {
                        return FileCallbackResult.ERR_FILE_NO_EXISTS;
                    }

                    FileInputStream is = new FileInputStream(path.toFile());

                    byte[] buf = new byte[8*1024];
                    int read = 0;
                    while ((read = is.read(buf)) != -1) {
                        os.write(buf, 0, read);
                    }

                    is.close();
                    os.close();
                } catch (IOException e) {
                    return FileCallbackResult.ERR_IO_EXCEPTION;
                }
                return FileCallbackResult.ERR_SUCCESS;
            }
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
        ERR_SUCCESS
    }

    public interface FileStoreCallback<T> extends Runnable {
        void setResult(T result);
    }
}
