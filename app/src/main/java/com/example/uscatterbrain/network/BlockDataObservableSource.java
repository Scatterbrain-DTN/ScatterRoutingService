package com.example.uscatterbrain.network;

import com.example.uscatterbrain.db.file.FileStore;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BooleanSupplier;
import io.reactivex.functions.Function;

/**
 * High level interface to a scatterbrain blockdata stream,
 * including blockheader and blocksequence packets.
 */
public class BlockDataObservableSource extends Observable<ScatterSerializable> {
    private BlockHeaderPacket mHeader;
    private int mBlockSize;
    private int mIndex;
    private boolean mToDisk;
    private InputStream mFragmentStream;
    private ByteString mToFingerprint;
    private ByteString mFromFingerprint;
    private Single<List<ByteString>> mHashList;
    private ByteString mApplication;
    private int mSessionID;
    private File mFile;
    private ByteString mSig;
    private Single<FileStore.FileCallbackResult> mFileResult;
    private Direction mDirection;
    private DirectExecutor mExecutor = new DirectExecutor();
    public enum Direction {
        SEND,
        RECEIVE
    }

    /**
     * default size of blocksequence packet
     */
    public static final int DEFAULT_BLOCK_SIZE = 1024*1024*1024;
    /**
     * max data size for packets stored in datastore without files
     */
    public static final long MAX_SIZE_NONFILE = 512*1024;


    protected BlockDataObservableSource() {
        super();
    }

    private BlockDataObservableSource(Builder builder) {
        try {
            this.mFragmentStream = new FileInputStream(builder.getFragmentFile());
        } catch(FileNotFoundException e) {
            e.printStackTrace();
            this.mFragmentStream = null;
        }
        this.mBlockSize = builder.getBlockSize();
        this.mHashList = builder.getHashList();
        this.mFromFingerprint = builder.getFrom();
        this.mToFingerprint = builder.getTo();
        this.mApplication = builder.getApplication();
        this.mSessionID = builder.getSessionID();
        this.mFile = builder.getFragmentFile();
        this.mDirection = Direction.SEND;
        this.mToDisk = builder.getToDisk();
        this.mSig = builder.getSig();
        mIndex = 0;
    }

    private BlockDataObservableSource(
            BlockHeaderPacket headerPacket,
            InputStream is,
            File file,
            Single<FileStore.FileCallbackResult> fileResult
    ) throws ParseException {
        this.mDirection = Direction.RECEIVE;
        if (file.exists()) {
            throw new ParseException("file exists", 0);
        }

        this.mFile = file;
        this.mHeader = headerPacket;

        if (mHeader == null) {
            throw new ParseException("failed to parse header", 0);
        }

        this.mBlockSize = mHeader.getBlockSize();
        this.mFromFingerprint = mHeader.getFromFingerprint();
        this.mToFingerprint = mHeader.getToFingerprint();
        this.mToDisk = mHeader.getToDisk();
        this.mApplication = ByteString.copyFrom(mHeader.getApplication());
        this.mSessionID = mHeader.getSessionID();
        this.mFileResult = fileResult;
    }

    public Single<Boolean> isHashValid() {
        //hashes should already be correct
        if (mFileResult != null && mDirection == Direction.RECEIVE) {
            return mFileResult
                    .map(fileCallbackResult -> {
                        if (fileCallbackResult == FileStore.FileCallbackResult.ERR_SUCCESS) {
                            return true;
                        } else {
                            return mDirection == Direction.SEND;
                        }
                    });

        } else {
            return Single.just(false);
        }
    }

    private Single<BlockHeaderPacket> asyncGetHeader() {

        if (this.mHeader != null) {
            return Single.just(mHeader);
        }

        return this.mHashList.map(hashlist -> {
            mHeader  = BlockHeaderPacket.newBuilder()
                    .setToDisk(mToDisk)
                    .setToFingerprint(mToFingerprint)
                    .setFromFingerprint(mFromFingerprint)
                    .setHashes(hashlist)
                    .setSessionID(mSessionID)
                    .setApplication(mApplication.toByteArray())
                    .setBlockSize(mBlockSize)
                    .setToDisk(mToDisk)
                    .setSig(mSig)
                    .build();
            return mHeader;
        });
    }

    public File getFile() {
        return mFile;
    }

    /**
     * Verifies the hashes of an existing chain of blocksequnce packets
     * NOTE: this stores everything in memory and is a bad idea.
     *
     * @param seqlist the seqlist
     * @return the boolean
     */
    public Completable verifySequence(List<BlockSequencePacket> seqlist) {
        return asyncGetHeader()
                .flatMap(headerPacket -> {
                    for (BlockSequencePacket s : seqlist) {
                        if (!s.verifyHash(headerPacket))
                            return Single.just(true);
                    }
                    return Single.error(new IllegalStateException("failed to verify hashes"));
                })
                .ignoreElement();

    }

    public Single<BlockHeaderPacket> getHeader() {
        return asyncGetHeader();
    }

    public Single<List<ByteString>> getHashes() {
        return mHashList;
    }

    /* implementation of Observable<ScatterSerializable> */

    @Override
    protected void subscribeActual(Observer<? super ScatterSerializable> observer) {
       asyncGetHeader()
               .toObservable()
               .concatMap((Function<BlockHeaderPacket, ObservableSource<ScatterSerializable>>) headerPacket -> next()
                       .repeatUntil(() -> mIndex > headerPacket.getHashList().size())
                       .toObservable())
               .subscribe(observer);
    }

    public Single<ScatterSerializable> next() {
           return asyncGetHeader()
                    .map(headerPacket -> {
                        ScatterSerializable result;
                        if (mIndex == 0) {
                            result = headerPacket;
                        } else {
                            // super hacky limited/capped inputstream. Only lets us read up to mBlocksize
                            InputStream is = new CappedInputStream(mFragmentStream, mBlockSize);
                            ByteString data = ByteString.readFrom(is);
                            BlockSequencePacket bs = new BlockSequencePacket.Builder()
                                    .setData(data)
                                    .setSequenceNumber(mIndex-1)
                                    .build();

                            if (!bs.verifyHash(headerPacket)) {
                                throw new IllegalStateException("failed to verify hash");
                            }

                            result = bs;
                        }

                        mIndex++;
                        return result;
                    });
    }

    public static Single<BlockDataObservableSource> parseFrom(InputStream inputStream, File file) {
        return  BlockHeaderPacket
                .parseFrom(inputStream)
                .flatMap(blockHeaderPacket -> {
                    Single<FileStore.FileCallbackResult> r = FileStore.getFileStore().insertFile(
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

    /**
     * creates a builder for ScatterDataPacket class
     *
     * @return the builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder for ScatterDataPacket class
     */
    public static class Builder {
        private int mBlockSize;
        private long mSize;
        private boolean mToDisk;
        private ByteString to;
        private ByteString from;
        private ByteString application;
        private int mSessionID;
        private File mFile;
        private ByteString mSig;
        private Single<List<ByteString>> mHashlist;

        /**
         * Instantiates a new Builder.
         */
        public Builder() {
            this.mBlockSize = DEFAULT_BLOCK_SIZE;
            this.mToDisk = false;
            this.mSessionID = 0;
            this.mToDisk = true;
        }

        /**
         * Sets recipient key fingerprint (optional)
         *
         * @param to the to
         * @return the to address
         */
        public Builder setToAddress(ByteString to) {
            this.to = to;
            return this;
        }

        /**
         * Sets sender key fingerprint (optional)
         *
         * @param from the from
         * @return the from address
         */
        public Builder setFromAddress(ByteString from) {
            this.from = from;
            return this;
        }

        /**
         * Sets session id.
         *
         * @param sessionID the session id used for bootstrapping to a new transport
         * @return the session id
         */
        public Builder setSessionID(int sessionID) {
            this.mSessionID = sessionID;
            return this;
        }

        /**
         * Sets application name
         *
         * @param application the application
         * @return the application
         */
        public Builder setApplication(String application) {
            this.application = ByteString.copyFromUtf8(application);
            return this;
        }

        /**
         * Sets file to retrieve BlockSequence packets from
         *
         * @param file the file
         * @return the fragment file
         */
        public Builder setFragmentFile(File file) {
            this.mFile = file;
            this.mToDisk = true;
            return this;
        }

        /**
         * Sets fragment count manually
         *
         * @param count the count
         * @return the fragment count
         */
        public Builder setFragmentCount(int count) {
            this.mSize = count;
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

        /**
         * Sets block size manually
         *
         * @param bs the bs
         * @return the block size
         */
        public Builder setBlockSize(int bs) {
            this.mBlockSize = bs;
            return this;
        }

        public Single<List<ByteString>> getHashList() {
            return mHashlist;
        }

        /**
         * Gets block size.
         *
         * @return the block size
         */
        public int getBlockSize() {
            return mBlockSize;
        }

        private long getCount() {
            return mSize;
        }

        /**
         * get the fragment file
         * @return reference to fragment file
         */
        public File getFragmentFile() {
            return mFile;
        }

        public ByteString getTo() {
            return to;
        }

        public ByteString getFrom() {
            return from;
        }

        public ByteString getApplication() {
            return application;
        }

        public int getSessionID() {
            return mSessionID;
        }

        public boolean getToDisk() { return mToDisk; }

        public ByteString getSig() { return mSig; }

        /**
         * Builds data packet.
         *
         * @return the scatter data packet
         */
        public BlockDataObservableSource build() {
            if (this.mToDisk && this.mFile != null)
                this.mSize = mFile.length();

            if (mFile== null) {
                return null;
            }

            if (!mFile.exists()) {
                return null;
            }

            if (mBlockSize <= 0) {
                return null;
            }

            mHashlist = FileStore.getFileStore().hashFile(mFile.toPath().toAbsolutePath(), mBlockSize);

            return new BlockDataObservableSource(this);

        }
    }

    static class DirectExecutor implements Executor {
        public void execute(Runnable r) {
            r.run();
        }
    }
}