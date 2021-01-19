package com.example.uscatterbrain.API;

import android.os.Parcel;
import android.os.Parcelable;
//TODO: add identity support
public class Identity implements Parcelable {

    protected Identity(Parcel in) {
    }

    public static final Creator<Identity> CREATOR = new Creator<Identity>() {
        @Override
        public Identity createFromParcel(Parcel in) {
            return new Identity(in);
        }

        @Override
        public Identity[] newArray(int size) {
            return new Identity[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
    }
}
