package android.hardware.bydauto;

/* JADX INFO: loaded from: classes.dex */
// ★DiLink5(2026-09-09): 이 stub이 진짜 프레임워크 BYDAutoEventValue를 shadow하는데 빈 클래스라, SDK(BodyworkManagerImpl→AbsTsMyCar.caseFeatureIds)가
//   value.floatValue 등에 접근하면 NoSuchFieldError→백그라운드 스레드 크래시(씨라이언7 무한 재시작·화면 깜빡임 실측). 진짜 클래스의 값 필드를 채워 SDK가 set/read해도 안 죽게 한다.
//   DiLink3는 부트클래스패스의 실클래스가 우선이라 이 stub은 런타임 미사용(무영향). 남는 필드는 무해. (반환형은 우리 코드가 리플렉션으로 읽으므로 필드명만 맞으면 됨)
public class BYDAutoEventValue {
    public int intValue;
    public float floatValue;
    public long longValue;
    public double doubleValue;
    public boolean booleanValue;
    public String stringValue;
}
