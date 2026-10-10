package com.cseini.bydsniffer;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

// 상시생존 이중화 — 일렉트로 RestarterJobService 방식. AlarmManager와 별개 경로로
// 주기 실행되며 SnifferService가 죽어 있으면 재기동. setPersisted라 재부팅 후에도 유지.
public class SnifferJobService extends JobService {
    private static final String TAG = "SnifferJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        if (SnifferService.getInstance() == null) {
            Log.i(TAG, "서비스 죽어있음 → 재기동");
            try {
                Intent i = new Intent(this, SnifferService.class);
                i.putExtra("src", "job" + params.getJobId());   // ★v44: 사인 추적 — 누가 부활시켰는지(1=15분주기, 2=워치독)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
                else startService(i);
            } catch (Exception e) {
                Log.e(TAG, "재기동 실패", e);
            }
        }
        // ★v44 워치독(id=2) 재장전 — 원샷잡은 발화하면 소멸하므로 여기서도 다시 예약(서비스 기동 실패 대비)
        try {
            android.app.job.JobScheduler js = (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            if (js != null) js.schedule(new android.app.job.JobInfo.Builder(2,
                    new android.content.ComponentName(this, SnifferJobService.class))
                    .setMinimumLatency(60_000L).setOverrideDeadline(180_000L).build());
        } catch (Throwable ignored) {}
        jobFinished(params, false);   // 주기잡이라 재예약 불필요
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
