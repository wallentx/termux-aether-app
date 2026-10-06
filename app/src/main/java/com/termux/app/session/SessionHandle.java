package com.termux.app.session;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

public final class SessionHandle implements Parcelable {
    public final int pid;
    public final String token;
    public final ParcelFileDescriptor master;

    SessionHandle(int pid, String token, ParcelFileDescriptor master) {
        this.pid = pid;
        this.token = token;
        this.master = master;
    }

    private SessionHandle(Parcel in) {
        pid = in.readInt();
        token = in.readString();
        master = ParcelFileDescriptor.CREATOR.createFromParcel(in);
    }

    @Override public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(pid);
        out.writeString(token);
        master.writeToParcel(out, flags);
    }

    public static final Creator<SessionHandle> CREATOR = new Creator<SessionHandle>() {
        @Override public SessionHandle createFromParcel(Parcel in) { return new SessionHandle(in); }
        @Override public SessionHandle[] newArray(int size) { return new SessionHandle[size]; }
    };
}
