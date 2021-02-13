package net.ballmerlabs.uscatterbrain.API;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler;

public class HandshakeResult implements Parcelable {
    protected HandshakeResult(Parcel in) {
        identities = in.readInt();
        messages = in.readInt();
        status = ScatterbrainScheduler.TransactionStatus.values()[in.readInt()];
    }

    public static final Creator<HandshakeResult> CREATOR = new Creator<HandshakeResult>() {
        @Override
        public HandshakeResult createFromParcel(Parcel in) {
            return new HandshakeResult(in);
        }

        @Override
        public HandshakeResult[] newArray(int size) {
            return new HandshakeResult[size];
        }
    };

    public final int identities;
    public final int messages;
    public final ScatterbrainScheduler.TransactionStatus status;

    public HandshakeResult(
            int identities,
            int messages,
            ScatterbrainScheduler.TransactionStatus status
    ) {
        this.status = status;
        this.messages = messages;
        this.identities = identities;
    }

    @NonNull
    public HandshakeResult from(HandshakeResult stats) {
        final ScatterbrainScheduler.TransactionStatus status;
        if (stats.status == ScatterbrainScheduler.TransactionStatus.STATUS_FAIL ||
                this.status == ScatterbrainScheduler.TransactionStatus.STATUS_FAIL) {
            status = ScatterbrainScheduler.TransactionStatus.STATUS_FAIL;
        } else {
            status = stats.status;
        }
        return new HandshakeResult(
                stats.identities + this.identities,
                stats.messages + this.messages,
                status
        );
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeInt(identities);
        parcel.writeInt(messages);
        parcel.writeInt(status.ordinal());
    }

}