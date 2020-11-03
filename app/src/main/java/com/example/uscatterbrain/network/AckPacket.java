package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class AckPacket implements ScatterSerializable {

    private final ScatterProto.Ack mAck;

    public enum Status {
        OK,
        ERR
    }

    private AckPacket(Builder builder) {
        ScatterProto.Ack.Builder b = ScatterProto.Ack.newBuilder();
        if (builder.message == null) {
            b.setMessageNull(true);
        } else {
            b.setMessageVal(builder.message);
        }

        b.setStatus(status2proto(builder.status));
        mAck = b.build();
    }

    private AckPacket(InputStream inputStream) throws IOException {
        this.mAck = ScatterProto.Ack.parseDelimitedFrom(inputStream);
    }

    public static Single<AckPacket> parseFrom(InputStream inputStream) {
        return Single.fromCallable(() -> new AckPacket(inputStream));
    }

    public static Single<AckPacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return AckPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static Single<AckPacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return AckPacket.parseFrom(observer).doFinally(observer::close);
    }

    private static Status proto2status(ScatterProto.Ack.Status status) {
        if (status == ScatterProto.Ack.Status.OK) {
            return Status.OK;
        }
        return Status.ERR;
    }

    private static ScatterProto.Ack.Status status2proto(Status status) {
        if (status == Status.OK) {
            return ScatterProto.Ack.Status.OK;
        }
        return ScatterProto.Ack.Status.ERR;
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            mAck.writeDelimitedTo(os);
            return os.toByteArray();
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    public ByteString getByteString() {
        return ByteString.copyFrom(getBytes());
    }

    @Override
    public Completable writeToStream(OutputStream os) {
        return Completable.fromAction(() -> mAck.writeDelimitedTo(os));
    }

    @Override
    public Flowable<byte[]> writeToStream() {
        return Bytes.from(new ByteArrayInputStream(getBytes()));
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mAck;
    }

    public String getReason() {
        if (mAck.getMessageNull()) {
            return "";
        } else {
            return mAck.getMessageVal();
        }
    }

    public Status getStatus() {
        return proto2status(mAck.getStatus());
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private String message;
        private Status status;

        private Builder() {

        }

        public Builder setMessage(String message) {
            this.message = message;
            return this;
        }

        public Builder setStatus(Status status) {
            this.status = status;
            return this;
        }

        public AckPacket build() {
            if (status == null) {
                throw new IllegalArgumentException("status should not be null");
            }

            return new AckPacket(this);
        }
    }
}
