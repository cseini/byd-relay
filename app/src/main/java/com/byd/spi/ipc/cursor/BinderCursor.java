package com.byd.spi.ipc.cursor;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

// ★v173 AppServer SPI 프레임워크의 BinderParcelable을 언마샬하기 위한 최소 이식 클래스.
//   FQN(com.byd.spi.ipc.cursor.BinderCursor$BinderParcelable)이 provider가 기록한 이름과 정확히 일치해야
//   Bundle.getParcelable("binder")가 우리 CREATOR로 readStrongBinder 해서 IBinder를 복원한다. (AppServer.apk 디컴파일 근거)
public class BinderCursor {
    public static final String KEY_BINDER = "binder";

    public static class BinderParcelable implements Parcelable {
        public static final Creator<BinderParcelable> CREATOR = new Creator<BinderParcelable>() {
            @Override public BinderParcelable createFromParcel(Parcel p) { return new BinderParcelable(p); }
            @Override public BinderParcelable[] newArray(int n) { return new BinderParcelable[n]; }
        };

        private IBinder mBinder;

        public BinderParcelable(IBinder b) { this.mBinder = b; }
        public BinderParcelable(Parcel p) { this.mBinder = p.readStrongBinder(); }

        public IBinder getBinder() { return this.mBinder; }

        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel p, int f) { p.writeStrongBinder(this.mBinder); }
    }
}
