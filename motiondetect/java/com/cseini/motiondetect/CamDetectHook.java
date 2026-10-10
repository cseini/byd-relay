package com.cseini.motiondetect;

import java.util.List;
import java.util.Locale;

// ★CAMRTC(실시간뷰)에 모션감지를 얹는 헬퍼 — 카메라 한 번 open으로 뷰(WebRTC) + 감지(MOG2) 동시.
//   테스트앱(byd-motiontest)이 한 프레임을 프리뷰+감지 둘 다 쓴 것과 동일 원리. 별도 CAMDETECT 카메라 open이
//   CAMRTC와 배타 충돌하던 문제를 없앤다.
//
// 사용 (camRtc 프레임 루프에서):
//   시작: hook = new CamDetectHook(frameW, frameH, rois, 0.03f, 16f, 200);  // 첫 프레임 w/h 확정 후
//   매프레임: String j = hook.feed(nv21, tsMs);  if (j != null) → DataChannel로 전송(아래 형식)
//   종료: hook.close();
//
// WebRTC/DataChannel 의존 없음 — feed()가 JSON 문자열만 반환. 전송은 호출측(camRtc)이 담당(모듈 독립성).
// 전송 JSON 형식: {"mot":[{"id":0,"pct":12,"vc":0.46,"trig":true}, ...]}
//   pct=움직임%(0~100 정수), vc=전경 세로중심(0~1, -1=없음), trig=임계 지속 트리거.
public final class CamDetectHook {
    private final MotionDetector md;
    private final long sendIntervalMs;
    private long lastSendMs = 0;

    public CamDetectHook(int frameW, int frameH, List<Roi> rois,
                         float learningRate, float varThreshold, long sendIntervalMs) {
        md = new MotionDetector(frameW, frameH);
        md.setRois(rois);
        md.setParams(learningRate, varThreshold, 0.9f);
        this.sendIntervalMs = sendIntervalMs;
    }

    // 프레임마다 호출. 감지는 매 프레임 수행(배경모델 유지), 전송은 sendIntervalMs로 스로틀.
    // 반환: 전송할 JSON(스로틀 통과 시) 또는 null(아직 전송 안 함).
    public String feed(byte[] nv21, long tsMs) {
        MotionDetector.Result[] r = md.onFrame(nv21, tsMs);
        if (tsMs - lastSendMs < sendIntervalMs) return null;
        lastSendMs = tsMs;
        StringBuilder sb = new StringBuilder("{\"mot\":[");
        for (int i = 0; i < r.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(r[i].roiId)
              .append(",\"pct\":").append(Math.round(r[i].motionPct * 100))
              .append(",\"vc\":").append(String.format(Locale.US, "%.2f", r[i].verticalCenter))
              .append(",\"trig\":").append(r[i].triggered ? "true" : "false")
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    // 민감도 단일 슬라이더 → 임계 실시간 반영(byd-motiontest applySensitivity와 동일 매핑을 호출측에서).
    public void setThreshold(float minPct, long sustainMs) { md.setThreshold(minPct, sustainMs); }
    public void setParams(float learningRate, float varThreshold) { md.setParams(learningRate, varThreshold, 0.9f); }

    // ROI 갱신(에디터에서 실시간 변경 시).
    public void setRois(List<Roi> rois) { md.setRois(rois); }

    public void close() { md.close(); }
}
