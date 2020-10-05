package com.example.uscatterbrain.network;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.InputStream;

import io.reactivex.Single;

public interface BlockDataSourceFactory {
    Single<BlockDataObservableSource> buildSource(InputStream inputStream, File file);

    Single<BlockDataObservableSource> buildSource(BuildOptions options);


    /**
     * default size of blocksequence packet
     */
    int DEFAULT_BLOCK_SIZE = 1024 * 1024 * 1024;
    /**
     * max data size for packets stored in datastore without files
     */
    long MAX_SIZE_NONFILE = 512 * 1024;

    enum Direction {
        SEND,
        RECEIVE
    }


    class BuildOptions {
        public final int block_size;
        public final boolean to_disk;
        public final long fragment_count;
        public final ByteString to_address;
        public final ByteString from_address;
        public final ByteString application;
        public final int session_id;
        public final File source_file;
        public final ByteString sig;

        private BuildOptions(
                int block_size,
                boolean to_disk,
                long fragment_count,
                ByteString to_address,
                ByteString from_address,
                ByteString application,
                int session_id,
                File source_file,
                ByteString sig
        ) {
            this.block_size = block_size;
            this.to_disk = to_disk;
            this.fragment_count = fragment_count;
            this.to_address = to_address;
            this.from_address = from_address;
            this.application = application;
            this.session_id = session_id;
            this.source_file = source_file;
            this.sig = sig;
        }

        public static class Builder {
            private int mBlockSize;
            private boolean mToDisk;
            private ByteString to;
            private ByteString from;
            private ByteString application;
            private long mFragmentCount;
            private int mSessionID;
            private File mFile;
            private ByteString mSig;

            public Builder setToAddress(ByteString to) {
                this.to = to;
                return this;
            }

            public Builder setFromAddress(ByteString from) {
                this.from = from;
                return this;
            }

            public Builder setSessionID(int sessionID) {
                this.mSessionID = sessionID;
                return this;
            }

            public Builder setApplication(String application) {
                this.application = ByteString.copyFromUtf8(application);
                return this;
            }

            public Builder setFragmentFile(File file) {
                this.mFile = file;
                this.mToDisk = true;
                return this;
            }

            public Builder setFragmentCount(int count) {
                this.mFragmentCount = count;
                return this;
            }

            public Builder setToDisk(boolean toDisk) {
                this.mToDisk = toDisk;
                return this;
            }

            public Builder setSig(ByteString sig) {
                this.mSig = sig;
                return this;
            }

            public Builder setBlockSize(int bs) {
                this.mBlockSize = bs;
                return this;
            }

            public BuildOptions build() {
                return new BuildOptions(
                        mBlockSize,
                        mToDisk,
                        mFragmentCount,
                        to,
                        from,
                        application,
                        mSessionID,
                        mFile,
                        mSig
                );
            }
        }
    }
}
