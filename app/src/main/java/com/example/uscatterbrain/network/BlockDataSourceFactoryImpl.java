package com.example.uscatterbrain.network;

import com.example.uscatterbrain.db.file.FileStore;
import com.example.uscatterbrain.db.file.FileStoreImpl;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.InputStream;
import java.util.List;

import javax.inject.Inject;

import io.reactivex.Single;

public class BlockDataSourceFactoryImpl implements BlockDataSourceFactory {

    private final FileStore fileStore;

    @Inject
    public BlockDataSourceFactoryImpl(
            FileStore fileStore
    ) {
        this.fileStore = fileStore;
    }

    @Override
    public Single<BlockDataObservableSource> buildSource(InputStream inputStream, File file) {
        return  BlockHeaderPacket
                .parseFrom(inputStream)
                .flatMap(blockHeaderPacket -> {
                    Single<FileStoreImpl.FileCallbackResult> r = fileStore.insertFile(
                            blockHeaderPacket,
                            inputStream,
                            blockHeaderPacket.getHashList().size(),
                            file.toPath().toAbsolutePath()
                    );
                    return Single.fromCallable(() -> new BlockDataObservableSource(
                            blockHeaderPacket,
                            inputStream,
                            file,
                            r
                    ));
                });
    }

    @Override
    public Single<BlockDataObservableSource> buildSource(BuildOptions options) {
        Single<List<ByteString>> hashList = fileStore.hashFile(
                options.source_file.toPath().toAbsolutePath(),
                options.block_size
        );
        return Single.fromCallable(() -> new BlockDataObservableSource(options, hashList));
    }
}
