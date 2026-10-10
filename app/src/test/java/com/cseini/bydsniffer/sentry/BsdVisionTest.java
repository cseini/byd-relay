package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BsdVisionTest {

    private static JSONArray sample() throws Exception {
        return new JSONArray("["
                + "{\"id\":\"20261009_031500_001\",\"ts\":1791000000000,\"why\":\"BSD 감지\",\"turn\":2,\"frameAgeMs\":120,\"sides\":["
                + "{\"side\":\"L\",\"old\":0.55,\"oldMs\":800,\"yolo\":0.70,\"yoloMs\":400,\"label\":1,\"img\":\"20261009_031500_001_L.jpg\"},"
                + "{\"side\":\"R\",\"old\":0.35,\"oldMs\":790,\"yolo\":0.10,\"yoloMs\":410,\"label\":0}]},"
                + "{\"id\":\"20261009_031600_002\",\"ts\":1791000060000,\"why\":\"깜빡이 켬(BSD 이미 감지)\",\"turn\":4,\"frameAgeMs\":300,\"sides\":["
                + "{\"side\":\"L\",\"old\":0.10,\"oldMs\":800,\"yolo\":0.45,\"yoloMs\":400,\"label\":1},"
                + "{\"side\":\"R\",\"old\":0.05,\"oldMs\":800,\"yolo\":0.05,\"yoloMs\":400,\"label\":null}]}]");
    }

    @Test
    public void confusionCountsOnlyLabeledSidesPerModel() throws Exception {
        JSONArray ev = sample();
        int[] old = BsdVision.confusion(ev, "old");     // {TP,FP,FN,TN}
        assertEquals(1, old[0]);   // L 0.55 차 있음 → TP
        assertEquals(1, old[1]);   // R 0.35 차 없음인데 ≥0.3 → FP
        assertEquals(1, old[2]);   // 2번째 L 0.10 차 있음인데 못 찾음 → FN
        assertEquals(0, old[3]);
        int[] yolo = BsdVision.confusion(ev, "yolo");
        assertEquals(2, yolo[0]);  // L 0.70, L 0.45 모두 TP
        assertEquals(0, yolo[1]);
        assertEquals(0, yolo[2]);
        assertEquals(1, yolo[3]);  // R 0.10 차 없음 → TN
    }

    @Test
    public void unknownLabelIsIgnored() throws Exception {
        JSONArray ev = new JSONArray("[{\"id\":\"x\",\"sides\":[{\"side\":\"L\",\"old\":0.9,\"yolo\":0.9,\"label\":-1}]}]");
        int[] c = BsdVision.confusion(ev, "old");
        assertEquals(0, c[0] + c[1] + c[2] + c[3]);
    }

    @Test
    public void pageShowsNewestFirstScoresButtonsAndEscapes() throws Exception {
        JSONArray ev = sample();
        ev.getJSONObject(0).put("why", "BSD <b>");
        String h = BsdVision.page(ev);
        assertTrue(h.indexOf("20261009_031600_002") < h.indexOf("20261009_031500_001"));   // 최신이 먼저
        assertTrue(h.contains("좌측 후방") && h.contains("우측 후방"));
        assertTrue(h.contains("현재 0.55(800ms) · YOLO 0.7(400ms)"));
        assertTrue(h.contains("차 있음") && h.contains("차 없음") && h.contains("모르겠음"));
        assertTrue(h.contains("/sentry/bsd/img?f=20261009_031500_001_L.jpg"));
        assertTrue(h.contains("BSD &lt;b&gt;"));
        assertTrue(h.contains("TP 1"));
    }

    @Test
    public void emptyPageExplains() {
        assertTrue(BsdVision.page(new JSONArray()).contains("아직 기록이 없습니다"));
    }

    @Test
    public void labelWritesAndValidatesIds() throws Exception {
        File root = java.nio.file.Files.createTempDirectory("bsd").toFile();
        File dir = new File(root, "BsdVision");
        assertTrue(dir.mkdirs());
        try (FileOutputStream fo = new FileOutputStream(new File(dir, "bsd.json"))) { fo.write(sample().toString().getBytes("UTF-8")); }
        BsdVision.get().setHost(new BsdVision.Host() {
            @Override public boolean enabled() { return true; }
            @Override public BsdVision.Frame frame() { return null; }
            @Override public byte[] sideView(BsdVision.Frame f, int side) { return null; }
            @Override public float[] oldBest(byte[] view) { return null; }
            @Override public float[][] yoloBoxes(byte[] view, float minScore) { return null; }
            @Override public int turnDirection() { return 1; }
            @Override public File dir() { return root; }
            @Override public void log(String msg) { }
        });
        assertTrue(BsdVision.get().label("20261009_031600_002", "R", 0));
        JSONArray back = BsdVision.get().events();
        assertEquals(0, back.getJSONObject(1).getJSONArray("sides").getJSONObject(1).getInt("label"));
        assertTrue(BsdVision.get().label("20261009_031600_002", "R", null));   // 취소
        assertTrue(BsdVision.get().events().getJSONObject(1).getJSONArray("sides").getJSONObject(1).isNull("label"));
        assertFalse(BsdVision.get().label("../etc", "R", 1));                  // 잘못된 id
        assertFalse(BsdVision.get().label("20261009_031600_002", "X", 1));     // 잘못된 칸
        assertFalse(BsdVision.get().label("20990101_000000_999", "L", 1));     // 없는 이벤트
        assertNull(BsdVision.get().imageFile("../../x.jpg"));
        assertNull(BsdVision.get().imageFile("20261009_031500_001_L.jpg"));    // 파일 없음
        BsdVision.get().setHost(null);
    }
}
