package com.cseini.bydsniffer;

import android.content.Context;
import android.content.ContextWrapper;

// BYD device getInstance(Context)에 넘길 컨텍스트 — 권한 체크를 전부 통과시켜 SDK 내부 enforce* NPE/거부 방지.
public class VehicleContextWrapper extends ContextWrapper {
    @Override public int checkCallingOrSelfPermission(String str) { return 0; }
    @Override public int checkCallingPermission(String str) { return 0; }
    @Override public int checkPermission(String str, int i, int i2) { return 0; }
    @Override public int checkSelfPermission(String str) { return 0; }
    @Override public void enforceCallingOrSelfPermission(String str, String str2) { }
    @Override public void enforceCallingPermission(String str, String str2) { }
    @Override public void enforcePermission(String str, int i, int i2, String str2) { }
    public VehicleContextWrapper(Context context) { super(context); }
}
