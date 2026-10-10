package com.cseini.motiondetect;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

// 킥 트렁크 감지 — EfficientDet-Lite0(사람게이트) + MoveNet Lightning(발목 포즈)로 발목 킥 스윙 판정.
//   MotionDetector(MOG2)와 별개 책임. 추론 비용 크므로 SnifferService가 트렁크 ROI에 모션 있을 때만 호출.
//   흐름: analyzePerson(사람 있나) → 있으면 analyzeKick(발목이 범퍼존으로 스윙했나, FSM).
public final class KickDetector {
    private static final String TAG = "KickDetector";
    private static final int EFF = 320, MOVE = 192;
    private static final int L_ANKLE = 15, R_ANKLE = 16;

    private Interpreter effDet, moveNet;
    // EfficientDet 출력 인덱스(기본=실측 순서 boxes,classes,scores,count; init서 shape로 보정)
    private int idxBoxes = 0, idxClasses = 1, idxScores = 2, idxCount = 3;

    private final ByteBuffer effIn = ByteBuffer.allocateDirect(EFF * EFF * 3).order(ByteOrder.nativeOrder());
    private final ByteBuffer moveIn = ByteBuffer.allocateDirect(MOVE * MOVE * 3).order(ByteOrder.nativeOrder());
    private final byte[] effRgb = new byte[EFF * EFF * 3];
    private final byte[] moveRgb = new byte[MOVE * MOVE * 3];

    // 튜닝 파라미터(실차) — 범퍼존은 트렁크 ROI 정규화 y 기준(0=위,1=아래).
    private float personScoreMin = 0.5f;
    private float ankleScoreMin = 0.3f;
    private float yEnter = 0.70f;   // 발목이 이 y 넘으면 존 진입
    private float yDeep = 0.85f;    // 존 안에서 이 y까지 내려가야(깊은 스윙) 유효 킥
    private long tDwellMin = 300, tDwellMax = 1200, cooldownMs = 5000;

    // FSM
    private long enterTs = 0;         // 발목 존 진입 시각(0=존 밖)
    private boolean reachedDeep = false;
    private long lastKickTs = 0;
    private String fsm = "IDLE";

    // 진단 최근값
    private volatile float lastPersonScore = 0f, lastAnkleConfL = 0f, lastAnkleConfR = 0f, lastAnkleY = -1f;
    private long bothAnkleTs = 0;              // ★C(2026-09-04): 양발 동시 확인 시각
    private long ankleVanishedTs = 0;          // ★한발 소실 시각 — 이후 양발 복귀 시 킥(찼다 뺌 완성)
    private long ankleVanishWindow = 700;      // ★양발→한발 소실 유효창 ms(직전 양발이었어야 오탐 방지)
    private long ankleReturnWindow = 1200;     // ★소실 후 양발 복귀 유효창 ms(넣었다 뺐다 완성 = 킥 확정)
    private volatile long lastInferMs = 0;

    // ★de-warp(2026-09-03, docs/DEWARP-00-PLAN.md) — 어안→직선 LUT를 킥 crop(EfficientDet/MoveNet 입력)에만 적용.
    //   MOG2·라이브뷰·ROI 좌표는 무관. 기본 OFF(무회귀 배포) → setDewarp로 A/B. LUT은 configureRoi 시 1회 생성.
    private long effLutHandle = 0, moveLutHandle = 0;
    private boolean dewarpEnabled = true;   // ★기본 ON(2026-09-08) — 후방 트렁크 어안이라 person 0.0~0.18로 사람 감지 실패("아무리 흔들어도 안 열림"). 어안→직선 remap으로 킥 crop 개선. fov 130°/margin 0.6 기본, RAW setDewarp로 튜닝.
    private float fovDeg = 130f, marginTopRatio = 0.6f;   // 튜닝 파라미터(그리드서치 대상). margin=ROI 위로 확장(몸통 일부 포함)
    private int lutSrcW = 0, lutSrcH = 0, roiX = 0, roiY = 0, roiW = 0, roiH = 0;

    public KickDetector(Context ctx) {
        try {
            effDet = new Interpreter(loadModel(ctx, "models/efficientdet_lite0.tflite"), opts());
            moveNet = new Interpreter(loadModel(ctx, "models/movenet_lightning_f16.tflite"), opts());
            mapEffOutputs();
            Log.i(TAG, "모델 로드 OK (eff outs=" + effDet.getOutputTensorCount() + " boxes=" + idxBoxes + " cls=" + idxClasses + " scr=" + idxScores + " cnt=" + idxCount + ")");
        } catch (Throwable t) {
            Log.e(TAG, "모델 로드 실패", t);
            close();
        }
    }

    public boolean isReady() { return effDet != null && moveNet != null; }

    private Interpreter.Options opts() {
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(4);
        return o;
    }

    private MappedByteBuffer loadModel(Context ctx, String path) throws Exception {
        AssetFileDescriptor afd = ctx.getAssets().openFd(path);
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            FileChannel fc = fis.getChannel();
            return fc.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
        } finally {
            afd.close();
        }
    }

    // EfficientDet 출력 순서는 빌드마다 다를 수 있어 shape로 보정. boxes=[1,N,4], count=[1], 나머지 2개[1,N]=classes(먼저),scores(다음).
    private void mapEffOutputs() {
        try {
            int seen2d = 0;
            int n = effDet.getOutputTensorCount();
            for (int i = 0; i < n; i++) {
                int[] s = effDet.getOutputTensor(i).shape();
                if (s.length == 3 && s[2] == 4) idxBoxes = i;
                else if (s.length == 1) idxCount = i;
                else if (s.length == 2) { if (seen2d == 0) idxClasses = i; else idxScores = i; seen2d++; }
            }
        } catch (Throwable ignored) {}
    }

    // ★접근 ROI용 사람 게이트(2026-09-04) — bbox를 항상 존중(de-warp LUT 안 탐). analyzePerson은 dewarp ON이면 트렁크 LUT로 remap해 bbox를 무시하므로 접근(role0) 게이트엔 이걸 써야 함.
    public float analyzePersonAt(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh) { return analyzePersonImpl(nv21, srcW, srcH, rx, ry, rw, rh, false); }
    // 사람 게이트 — 트렁크 ROI에서 최고 person(class0) score. 없으면 0.
    public float analyzePerson(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh) { return analyzePersonImpl(nv21, srcW, srcH, rx, ry, rw, rh, true); }
    private float analyzePersonImpl(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh, boolean allowDewarp) {
        if (effDet == null) return 0f;
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            if (allowDewarp && useDewarp(effLutHandle, srcW, srcH)) YuvNative.remapRgbWithLut(effLutHandle, nv21, srcW, srcH, effRgb);
            else YuvNative.extractRgbRoi(nv21, srcW, srcH, rx, ry, rw, rh, EFF, EFF, effRgb);
            effIn.clear(); effIn.put(effRgb); effIn.rewind();
            float[][][] boxes = new float[1][25][4];
            float[][] classes = new float[1][25];
            float[][] scores = new float[1][25];
            float[] count = new float[1];
            Map<Integer, Object> out = new HashMap<>();
            out.put(idxBoxes, boxes); out.put(idxClasses, classes); out.put(idxScores, scores); out.put(idxCount, count);
            effDet.runForMultipleInputsOutputs(new Object[]{effIn}, out);
            int nd = Math.min(25, Math.max(0, (int) count[0]));
            float best = 0f;
            for (int i = 0; i < nd; i++) {
                if (Math.round(classes[0][i]) == 0 && scores[0][i] > best) best = scores[0][i];
            }
            lastPersonScore = best;
            lastInferMs = android.os.SystemClock.elapsedRealtime() - t0;
            return best;
        } catch (Throwable t) {
            Log.e(TAG, "analyzePerson", t);
            return 0f;
        }
    }

    // ★전방 객체인식(2026-09-08) — 임의 ROI에서 target COCO 클래스의 최고 detection. de-warp 안 탐(별도AVM 정상화각 우선, 순정 어안 de-warp는 후속 튜닝).
    //   반환 [score(0~1), cx, cy, w, h] — ROI 정규화 0~1(EfficientDet box=[ymin xmin ymax xmax]). 매칭 없으면 null.
    //   COCO 0-idx: person0 car2 bus5 truck7 traffic-light9. handleFrontAlert가 앞차(2/5/7)·신호등(9)에 사용.
    public float[] analyzeClass(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh, int[] targetClasses) {
        if (effDet == null) return null;
        try {
            YuvNative.extractRgbRoi(nv21, srcW, srcH, rx, ry, rw, rh, EFF, EFF, effRgb);
            effIn.clear(); effIn.put(effRgb); effIn.rewind();
            float[][][] boxes = new float[1][25][4];
            float[][] classes = new float[1][25];
            float[][] scores = new float[1][25];
            float[] count = new float[1];
            Map<Integer, Object> out = new HashMap<>();
            out.put(idxBoxes, boxes); out.put(idxClasses, classes); out.put(idxScores, scores); out.put(idxCount, count);
            effDet.runForMultipleInputsOutputs(new Object[]{effIn}, out);
            int nd = Math.min(25, Math.max(0, (int) count[0]));
            float best = 0f; float[] bb = null;
            for (int i = 0; i < nd; i++) {
                int c = Math.round(classes[0][i]);
                boolean match = false;
                for (int tc : targetClasses) if (c == tc) { match = true; break; }
                if (match && scores[0][i] > best) {
                    best = scores[0][i];
                    float ymin = boxes[0][i][0], xmin = boxes[0][i][1], ymax = boxes[0][i][2], xmax = boxes[0][i][3];
                    bb = new float[]{best, (xmin + xmax) / 2f, (ymin + ymax) / 2f, Math.max(0f, xmax - xmin), Math.max(0f, ymax - ymin)};
                }
            }
            return bb;
        } catch (Throwable t) {
            return null;
        }
    }

    // ★다중 후보 버전(2026-09-11) — analyzeClass는 ROI 안에서 '가장 확실한 차/신호등' 1개만 돌려줘서,
    //   전방 앞차 추적이 옆 차선 차가 더 크게/높은 conf로 잡히는 순간 그쪽으로 조용히 스왑되는 문제가
    //   있었다(FrontAlertTracker가 다중 후보 중 추적 연속성 있는 것만 골라 쓰도록 설계). 동작은 기존
    //   analyzeClass와 동일(같은 모델 실행·같은 ROI/좌표계), minConf 이상인 모든 매칭 detection을
    //   conf 내림차순으로 반환할 뿐 — analyzeClass 자체는 건드리지 않는다.
    //   반환 각 원소 [score, cx, cy, w, h] — ROI 정규화. 매칭 없으면 길이 0 배열.
    public float[][] analyzeClassAll(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh, int[] targetClasses, float minConf) {
        if (effDet == null) return new float[0][];
        try {
            YuvNative.extractRgbRoi(nv21, srcW, srcH, rx, ry, rw, rh, EFF, EFF, effRgb);
            effIn.clear(); effIn.put(effRgb); effIn.rewind();
            float[][][] boxes = new float[1][25][4];
            float[][] classes = new float[1][25];
            float[][] scores = new float[1][25];
            float[] count = new float[1];
            Map<Integer, Object> out = new HashMap<>();
            out.put(idxBoxes, boxes); out.put(idxClasses, classes); out.put(idxScores, scores); out.put(idxCount, count);
            effDet.runForMultipleInputsOutputs(new Object[]{effIn}, out);
            int nd = Math.min(25, Math.max(0, (int) count[0]));
            java.util.List<float[]> list = new java.util.ArrayList<>();
            for (int i = 0; i < nd; i++) {
                int c = Math.round(classes[0][i]);
                boolean match = false;
                for (int tc : targetClasses) if (c == tc) { match = true; break; }
                if (match && scores[0][i] >= minConf) {
                    float ymin = boxes[0][i][0], xmin = boxes[0][i][1], ymax = boxes[0][i][2], xmax = boxes[0][i][3];
                    list.add(new float[]{scores[0][i], (xmin + xmax) / 2f, (ymin + ymax) / 2f, Math.max(0f, xmax - xmin), Math.max(0f, ymax - ymin)});
                }
            }
            list.sort((a, b) -> Float.compare(b[0], a[0]));
            return list.toArray(new float[0][]);
        } catch (Throwable t) {
            return new float[0][];
        }
    }

    // 발목 포즈 + 킥 FSM. person 확인 후 매(게이트된) 프레임 호출 → 스윙 추적. kick이면 KickResult.kick=true.
    public KickResult analyzeKick(byte[] nv21, int srcW, int srcH, int rx, int ry, int rw, int rh, long nowMs) {
        KickResult kr = new KickResult();
        kr.personScore = lastPersonScore;
        kr.person = lastPersonScore >= personScoreMin;
        if (moveNet == null) { kr.fsm = "NOMODEL"; return kr; }
        try {
            if (useDewarp(moveLutHandle, srcW, srcH)) YuvNative.remapRgbWithLut(moveLutHandle, nv21, srcW, srcH, moveRgb);
            else YuvNative.extractRgbRoi(nv21, srcW, srcH, rx, ry, rw, rh, MOVE, MOVE, moveRgb);
            moveIn.clear(); moveIn.put(moveRgb); moveIn.rewind();
            float[][][][] kp = new float[1][1][17][3];
            moveNet.run(moveIn, kp);
            float lY = kp[0][0][L_ANKLE][0], lC = kp[0][0][L_ANKLE][2];
            float rY = kp[0][0][R_ANKLE][0], rC = kp[0][0][R_ANKLE][2];
            lastAnkleConfL = lC; lastAnkleConfR = rC;
            kr.ankleConfL = lC; kr.ankleConfR = rC;
            // 신뢰있는 발목 중 가장 아래(y 큰) 것 채택
            float ay = -1f;
            if (lC >= ankleScoreMin) ay = lY;
            if (rC >= ankleScoreMin && rY > ay) ay = rY;
            lastAnkleY = ay; kr.ankleY = ay;

            // ★C(2026-09-04): 양발→한발 소실→양발 복귀 = 킥. 킥하면 한 발이 범퍼 밑으로 들어가(confidence 소실) 뺀다(복귀). y스윙(실차 51~62% 무변화)보다 명확 + "찼다 뺐다" 완전패턴만 인정해 우연한 가림 오탐 방지.
            boolean lSeen = lC >= ankleScoreMin, rSeen = rC >= ankleScoreMin;
            if (lSeen && rSeen) {
                // 양발 — 직전 한발 소실이 window 내였으면 복귀 = 킥 확정(넣었다 뺐다)
                if (ankleVanishedTs != 0 && (nowMs - ankleVanishedTs) <= ankleReturnWindow && (nowMs - lastKickTs) > cooldownMs) {
                    lastKickTs = nowMs; kr.kick = true; kr.kickConfidence = Math.max(lC, rC); fsm = "KICK_VANISH"; ankleVanishedTs = 0; bothAnkleTs = nowMs;
                    kr.fsm = fsm; return kr;
                }
                bothAnkleTs = nowMs; ankleVanishedTs = 0;
            } else if ((lSeen ^ rSeen) && bothAnkleTs != 0 && (nowMs - bothAnkleTs) <= ankleVanishWindow) {
                // 양발→한발 소실(직전 양발이었음) — 소실 시각 기록, 양발 복귀 대기
                if (ankleVanishedTs == 0) ankleVanishedTs = nowMs;
            }

            boolean inZone = ay >= 0f && ay > yEnter;
            if (inZone) {
                if (enterTs == 0) { enterTs = nowMs; reachedDeep = false; fsm = "ENTER"; }
                if (ay > yDeep) { reachedDeep = true; fsm = "DEEP"; }
                if (nowMs - enterTs > tDwellMax) { enterTs = 0; reachedDeep = false; fsm = "STAND"; } // 오래 머묾=서있음
            } else {
                if (enterTs != 0) {
                    long dwell = nowMs - enterTs;
                    boolean valid = reachedDeep && dwell >= tDwellMin && dwell <= tDwellMax
                            && (nowMs - lastKickTs) > cooldownMs;
                    enterTs = 0; reachedDeep = false;
                    if (valid) { lastKickTs = nowMs; kr.kick = true; kr.kickConfidence = Math.max(lC, rC); fsm = "KICK"; }
                    else fsm = "EXIT";
                } else {
                    fsm = "IDLE";
                }
            }
            kr.fsm = fsm;
            return kr;
        } catch (Throwable t) {
            Log.e(TAG, "analyzeKick", t);
            kr.fsm = "ERR";
            return kr;
        }
    }

    public float lastPersonScore() { return lastPersonScore; }
    public float lastAnkleConfL() { return lastAnkleConfL; }
    public float lastAnkleConfR() { return lastAnkleConfR; }
    public float lastAnkleY() { return lastAnkleY; }
    public long lastInferMs() { return lastInferMs; }
    public String fsm() { return fsm; }

    public void setPersonScoreMin(float v) { personScoreMin = v; }
    public void setAnkleScoreMin(float v) { ankleScoreMin = v; }
    public void setBumperZone(float enter, float deep) { yEnter = enter; yDeep = deep; }
    public void setDwell(long minMs, long maxMs) { tDwellMin = minMs; tDwellMax = maxMs; }
    public void setCooldown(long ms) { cooldownMs = ms; }

    // ★트렁크 bbox 확정 직후 1회 호출(SnifferService kd 생성 직후) — ROI(+위쪽 마진) 각도창 기준 de-warp LUT 사전계산.
    public void configureRoi(int srcW, int srcH, int rx, int ry, int rw, int rh) {
        roiX = rx; roiY = ry; roiW = rw; roiH = rh; lutSrcW = srcW; lutSrcH = srcH;
        rebuildLuts();
    }
    private void rebuildLuts() {
        freeLuts();
        if (lutSrcW <= 0 || lutSrcH <= 0 || roiW <= 0 || roiH <= 0) return;
        int mrgY = (int) (roiH * marginTopRatio);           // 위쪽 확장 — MoveNet은 엉덩이·무릎이 보이면 발목 신뢰도↑(관절 상호참조)
        int ery = Math.max(0, roiY - mrgY), erh = roiH + (roiY - ery);
        float cx = lutSrcW / 2f, cy = lutSrcH / 2f;          // 주점=풀프레임 중심(가정, 튜닝 대상)
        effLutHandle  = YuvNative.buildDewarpLut(lutSrcW, lutSrcH, roiX, ery, roiW, erh, fovDeg, cx, cy, EFF, EFF, 0f, false, 0f, 0f);    // ★656 rollDeg 0=종전 ★712 k 0=등거리
        moveLutHandle = YuvNative.buildDewarpLut(lutSrcW, lutSrcH, roiX, ery, roiW, erh, fovDeg, cx, cy, MOVE, MOVE, 0f, false, 0f, 0f);
        Log.i(TAG, "dewarp LUT fov=" + fovDeg + " margin=" + marginTopRatio + " roi=[" + roiX + "," + ery + "," + roiW + "," + erh + "] eff=" + (effLutHandle != 0) + " move=" + (moveLutHandle != 0));
    }
    private void freeLuts() {
        if (effLutHandle != 0) { YuvNative.freeDewarpLut(effLutHandle); effLutHandle = 0; }
        if (moveLutHandle != 0) { YuvNative.freeDewarpLut(moveLutHandle); moveLutHandle = 0; }
    }
    // ★튜닝/A-B(재빌드 없이 워커 명령으로) — fov/margin이 바뀌면 LUT 재생성(ROI 이미 설정된 경우).
    public void setDewarp(float fov, float marginTop, boolean enabled) {
        boolean rebuild = (fov != fovDeg) || (marginTop != marginTopRatio);
        fovDeg = fov; marginTopRatio = marginTop; dewarpEnabled = enabled;
        if (rebuild && lutSrcW > 0) rebuildLuts();
    }
    public boolean dewarpEnabled() { return dewarpEnabled; }
    public float fovDeg() { return fovDeg; }
    // LUT은 빌드 해상도 전용 — 해상도 다르면 기존 크롭으로 폴백(안전)
    private boolean useDewarp(long handle, int srcW, int srcH) { return dewarpEnabled && handle != 0 && srcW == lutSrcW && srcH == lutSrcH; }

    public void close() {
        try { if (effDet != null) effDet.close(); } catch (Throwable ignored) {}
        try { if (moveNet != null) moveNet.close(); } catch (Throwable ignored) {}
        freeLuts();
        effDet = null; moveNet = null;
    }

    public static final class KickResult {
        public boolean person = false;
        public float personScore = 0f;
        public boolean kick = false;
        public float kickConfidence = 0f;
        public float ankleConfL = 0f, ankleConfR = 0f, ankleY = -1f;
        public String fsm = "IDLE";
    }
}
