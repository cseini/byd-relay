package com.cseini.bydsniffer.sentry;

// 인코더가 낸 압축 샘플 1개(불변). data는 복사본이라 받은 쪽이 소유한다.
public final class EncodedSample {
    public final byte[] data;
    public final long ptsUs;
    public final boolean keyFrame;
    public final boolean codecConfig;

    public EncodedSample(byte[] data, long ptsUs, boolean keyFrame, boolean codecConfig) {
        this.data = data;
        this.ptsUs = ptsUs;
        this.keyFrame = keyFrame;
        this.codecConfig = codecConfig;
    }
}
