package com.termux.app.session;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import java.io.Closeable;
import java.io.IOException;

/** App ends of three independent pipes; no PTY, echo, or newline conversion. */
public final class BackgroundHandle implements Parcelable, Closeable {
    public final int pid;
    public final String token;
    public final ParcelFileDescriptor stdin, stdout, stderr;

    BackgroundHandle(int pid, String token, ParcelFileDescriptor stdin,
                     ParcelFileDescriptor stdout, ParcelFileDescriptor stderr) {
        this.pid = pid;
        this.token = token;
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    private BackgroundHandle(Parcel in) {
        pid = in.readInt();
        token = in.readString();
        stdin = ParcelFileDescriptor.CREATOR.createFromParcel(in);
        stdout = ParcelFileDescriptor.CREATOR.createFromParcel(in);
        stderr = ParcelFileDescriptor.CREATOR.createFromParcel(in);
    }

    @Override public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(pid);
        out.writeString(token);
        stdin.writeToParcel(out, flags);
        stdout.writeToParcel(out, flags);
        stderr.writeToParcel(out, flags);
    }

    @Override public void close() {
        for (ParcelFileDescriptor fd : new ParcelFileDescriptor[]{stdin, stdout, stderr}) {
            if (fd != null) try { fd.close(); } catch (IOException ignored) {}
        }
    }

    public static final Creator<BackgroundHandle> CREATOR = new Creator<BackgroundHandle>() {
        @Override public BackgroundHandle createFromParcel(Parcel in) { return new BackgroundHandle(in); }
        @Override public BackgroundHandle[] newArray(int size) { return new BackgroundHandle[size]; }
    };
}
