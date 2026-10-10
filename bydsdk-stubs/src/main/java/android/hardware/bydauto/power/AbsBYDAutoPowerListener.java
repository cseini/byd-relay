package android.hardware.bydauto.power;

import android.hardware.IBYDAutoEvent;
import android.hardware.IBYDAutoListener;
import android.hardware.bydauto.BYDAutoEventValue;

// 런타임엔 프레임워크의 실제 클래스가 사용됨(컴파일용 스텁).
public abstract class AbsBYDAutoPowerListener implements IBYDAutoListener {
    @Override public void onDataChanged(IBYDAutoEvent e) {}
    @Override public void onDataEventChanged(int i, BYDAutoEventValue v) {}
    @Override public void onError(int i, String s) {}
}
