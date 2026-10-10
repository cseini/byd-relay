package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class SentryVoiceTest {
    @Test
    public void clipsAreIntroCountOutro() {
        assertEquals(Arrays.asList("intro", "u1", "outro"), SentryVoice.clipIds(1));      // 한 건이
        assertEquals(Arrays.asList("intro", "u9", "outro"), SentryVoice.clipIds(9));      // 아홉 건이
        assertEquals(Arrays.asList("intro", "t10", "outro"), SentryVoice.clipIds(10));    // 열 건이
        assertEquals(Arrays.asList("intro", "p10", "u1", "outro"), SentryVoice.clipIds(11));   // 열 + 한 건이
        assertEquals(Arrays.asList("intro", "p10", "u2", "outro"), SentryVoice.clipIds(12));   // 열 + 두 건이
        assertEquals(Arrays.asList("intro", "t20", "outro"), SentryVoice.clipIds(20));    // 스무 건이
        assertEquals(Arrays.asList("intro", "p20", "u1", "outro"), SentryVoice.clipIds(21));   // 스물 + 한 건이
        assertEquals(Arrays.asList("intro", "p30", "u5", "outro"), SentryVoice.clipIds(35));   // 서른 + 다섯 건이
        assertEquals(Arrays.asList("intro", "t90", "outro"), SentryVoice.clipIds(90));
        assertEquals(Arrays.asList("intro", "p90", "u9", "outro"), SentryVoice.clipIds(99));
        assertEquals(Arrays.asList("intro", "c_over", "outro"), SentryVoice.clipIds(100));
        assertEquals(Arrays.asList("intro", "c_over", "outro"), SentryVoice.clipIds(5000));
    }

    @Test
    public void nothingIsReadForZeroOrNegative() {
        assertTrue(SentryVoice.clipIds(0).isEmpty());
        assertTrue(SentryVoice.clipIds(-3).isEmpty());
    }

    @Test
    public void screenLine() {
        assertEquals("주차 중 센트리 녹화 1건이 있습니다", SentryVoice.line(1));
        assertEquals("주차 중 센트리 녹화 12건이 있습니다", SentryVoice.line(12));
        assertEquals("주차 중 센트리 녹화 100건 이상이 있습니다", SentryVoice.line(100));
        assertEquals("주차 중 센트리 녹화 100건 이상이 있습니다", SentryVoice.line(2500));
    }

    @Test
    public void everyClipIdHasAnAssetFileAndNothingIsExtra() {
        File dir = new File("src/main/assets/" + SentryVoice.ASSET_DIR);
        assertTrue("조각 폴더가 없다: " + dir.getAbsolutePath(), dir.isDirectory());
        Set<String> have = new HashSet<>();
        for (String n : dir.list()) if (n.endsWith(".wav")) have.add(n.substring(0, n.length() - 4));
        Set<String> want = new HashSet<>(SentryVoice.allClipIds());
        for (String id : want) assertTrue("조각 파일이 없다: " + id, have.contains(id));
        for (String id : have) assertTrue("코드에 없는 조각 파일: " + id, want.contains(id));
        assertEquals(30, want.size());
    }

    @Test
    public void everyPossibleSequenceUsesKnownClipsOnly() {
        Set<String> want = new HashSet<>(SentryVoice.allClipIds());
        for (int c = 1; c <= 120; c++) for (String id : SentryVoice.clipIds(c)) assertTrue(id, want.contains(id));
    }
}
