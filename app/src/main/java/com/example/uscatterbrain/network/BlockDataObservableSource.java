package com.example.uscatterbrain.network;

import com.example.uscatterbrain.db.file.FileStoreImpl;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.functions.Function;
import com.example.uscatterbrain.network.BlockDataSourceFactory.Direction;

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
    private Single<FileStoreImpl.FileCallbackResult> mFileResult;
    private BlockDataSourceFactory.Direction mDirection;



    protected BlockDataObservableSource() {
        super();
    }

    public BlockDataObservableSource(BlockDataSourceFactory.BuildOptions builder, Single<List<ByteString>> hashList) {
        try {
            this.mFragmentStream = new FileInputStream(builder.source_file);
        } catch(FileNotFoundException e) {
            e.printStackTrace();
            this.mFragmentStream = null;
        }
        this.mBlockSize = builder.block_size;
        this.mHashList = hashList;
        this.mFromFingerprint = builder.from_address;
        this.mToFingerprint = builder.to_address;
        this.mApplication = builder.application;
        this.mSessionID = builder.session_id;
        this.mFile = builder.source_file;
        this.mDirection = Direction.SEND;
        this.mToDisk = builder.to_disk;
        this.mSig = builder.sig;
        mIndex = 0;
    }

    public BlockDataObservableSource(
            BlockHeaderPacket headerPacket,
            InputStream is,
            File file,
            Single<FileStoreImpl.FileCallbackResult> fileResult
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
        this.mHashList = Single.just(headerPacket.getHashList());
    }

    public Single<Boolean> isHashValid() {
        //hashes should already be correct
        if (mFileResult != null && mDirection == Direction.RECEIVE) {
            return mFileResult
                    .map(fileCallbackResult -> {
                        if (fileCallbackResult == FileStoreImpl.FileCallbackResult.ERR_SUCCESS) {
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
}
