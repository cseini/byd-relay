package com.cseini.bydsniffer.sentry;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 센트리 웹 재생 페이지(/sentry/play)·날짜별 목록 페이지(/sentry/web) HTML 생성 — 폰 앱 WebView가 연다.
// 외부 리소스 없음. 값은 모두 숫자이거나 SentryIndex.isValidId를 통과한 id라 이스케이프가 필요 없다.
// JS는 문자열 따옴표를 작은따옴표로만 쓴다(자바 문자열 안에서 이스케이프를 피하려고).
final class SentryPlayPage {
    private SentryPlayPage() {}

    private static final String CSS = ""
            + "*{box-sizing:border-box}"
            + "body{margin:0;background:#0b0f14;color:#e6edf3;font-family:-apple-system,'Apple SD Gothic Neo','Noto Sans KR',sans-serif;-webkit-text-size-adjust:100%}"
            + ".w{max-width:900px;margin:0 auto;padding:12px 12px 32px}"
            + "h1{font-size:18px;margin:4px 0 2px}"
            + ".sub,.pos,.info,.path{color:#9aa7b4;font-size:13px}"
            + "#v{display:block;width:100%;aspect-ratio:4/3;background:#000;object-fit:contain;margin-top:10px;border-radius:8px}"
            + ".pos{text-align:center;margin:8px 0 4px;font-variant-numeric:tabular-nums}"
            + ".dots{display:flex;justify-content:center;flex-wrap:wrap;gap:8px;margin:6px 0 10px;min-height:14px}"
            + ".dot{width:14px;height:14px;padding:0;border-radius:50%;border:0;background:#33414f}"
            + ".dot.on{background:#4da3ff}"
            + ".bar{display:flex;gap:8px}"
            + ".bar button{flex:1;padding:12px 4px;font-size:15px;color:#e6edf3;background:#1c2733;border:0;border-radius:8px}"
            + ".msg{min-height:20px;margin:10px 0;color:#ffb84d;font-size:14px;text-align:center}"
            + ".info{margin-top:6px;line-height:1.7}"
            + ".path{margin-top:6px;word-break:break-all}"
            + ".del{width:100%;margin-top:16px;padding:12px;font-size:15px;color:#ff6b6b;background:#1c2733;border:0;border-radius:8px}"
            + ".del:disabled{color:#6b7885}"
            + ".cal{background:#151d26;border-radius:8px;padding:8px;margin:6px 0 12px}"
            + ".cal .mh{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px}"
            + ".cal .mh a{color:#4da3ff;text-decoration:none;font-size:15px;padding:6px 10px}"
            + ".cal table{width:100%;border-collapse:collapse;table-layout:fixed;text-align:center}"
            + ".cal th{font-size:12px;color:#9aa7b4;font-weight:normal;padding:2px 0}"
            + ".cal td{height:42px;font-size:14px;color:#6b7885;vertical-align:middle}"
            + ".cal td a{display:block;color:#e6edf3;text-decoration:none;padding:4px 0;border-radius:6px}"
            + ".cal td a i{display:block;font-style:normal;font-size:10px;color:#4da3ff}"
            + ".cal td.sel a,.cal td.sel{background:#1f3b57;border-radius:6px}"
            + ".cal td.today{outline:1px solid #4da3ff;border-radius:6px}"
            + ".tl{position:relative;height:18px;background:#1c2733;border-radius:9px;margin:4px 0 6px;overflow:hidden}"
            + ".tl .mk{position:absolute;top:3px;height:12px;min-width:8px;border:0;border-radius:6px;padding:0;background:#4da3ff}"
            + ".tl .mk.k1{background:#ffb84d}.tl .mk.k2{background:#5ed67a}.tl .mk.k3{background:#7a8794}"
            + ".ml{display:flex;flex-wrap:wrap;gap:6px;margin:4px 0 8px;justify-content:center}"
            + ".ml button{padding:6px 10px;font-size:13px;color:#e6edf3;background:#1c2733;border:0;border-radius:14px}"
            + ".nav{display:flex;justify-content:space-between;align-items:center;margin:6px 0 12px}"
            + ".nav a{color:#4da3ff;text-decoration:none;font-size:15px;padding:8px 6px}"
            + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}"
            + ".c{display:block;color:#e6edf3;text-decoration:none;background:#151d26;border-radius:8px;overflow:hidden}"
            + ".c img{display:block;width:100%;aspect-ratio:4/3;object-fit:cover;background:#000}"
            + ".c div{padding:6px 8px;font-size:13px}"
            + "@media (orientation:landscape) and (max-height:600px){.w{max-width:none}#v{aspect-ratio:auto;max-height:85vh}}";

    private static final String PLAY_JS = ""
            + "var D=__DATA__;\n"
            + "var v=document.getElementById('v'),segs=D.segs,off=[],tot=0,cur=0,seekTo=null,gone=false;\n"
            + "for(var i=0;i<segs.length;i++){off.push(tot);tot+=segs[i].d;}\n"
            + "function f(s){s=Math.max(0,Math.floor(s));var m=Math.floor(s/60),x=s%60;return(m<10?'0':'')+m+':'+(x<10?'0':'')+x;}\n"
            + "function $(id){return document.getElementById(id);}\n"
            + "function msg(t){$('msg').textContent=t;}\n"
            + "function url(i){return '/sentry/file?id='+encodeURIComponent(D.id)+'&seg='+segs[i].n+G;}\n"
            + "function mark(){var ds=$('dots').children;for(var i=0;i<ds.length;i++){ds[i].className=i===cur?'dot on':'dot';}}\n"
            + "function showPos(){$('pos').textContent=f(off[cur]+(v.currentTime||0))+' / '+f(tot);}\n"
            + "function tryPlay(){var p=v.play();if(p&&p.catch){p.catch(function(e){if(e&&e.name==='AbortError')return;msg('자동 재생이 막혔습니다. 재생 버튼을 눌러 주세요.');});}}\n"
            + "function go(i,t,play){cur=i;seekTo=t>0?t:null;msg('');v.src=url(i);mark();showPos();if(play)tryPlay();}\n"
            + "function applySeek(){if(seekTo!==null){var t=seekTo;seekTo=null;try{v.currentTime=t;}catch(e){}}}\n"
            + "function skip(dx){var t=off[cur]+(v.currentTime||0)+dx;if(t<0)t=0;if(t>tot-0.3)t=Math.max(0,tot-0.3);\n"
            + " var k=segs.length-1;while(k>0&&off[k]>t)k--;var inner=t-off[k];\n"
            + " if(k===cur){v.currentTime=inner;}else{go(k,inner,!v.paused);}}\n"
            + "function seekAbs(t){skip(t-(off[cur]+(v.currentTime||0)));}\n"
            + "var M=D.marks||[],POS=['좌상','우상','좌하','우하'],KIND=['움직임','차량','사람','조명 의심'];\n"
            + "(function(){var tl=$('tl'),ml=$('ml');if(!M.length){tl.style.display='none';ml.style.display='none';return;}\n"
            + " for(var i=0;i<M.length;i++){var m=M[i],t=m[0],c=m[2]<0?'영역':POS[m[2]],lb=f(t)+' '+c+' '+KIND[m[1]]+' '+m[3]+'%';\n"
            + "  var b=document.createElement('button');b.className='mk k'+m[1];b.style.left=(100*t/Math.max(tot,1))+'%';b.style.width=Math.max(1.2,100*m[4]/Math.max(tot,1))+'%';b.title=lb;b.setAttribute('aria-label',lb);\n"
            + "  b.onclick=(function(x){return function(){seekAbs(Math.max(0,x-1));if(v.paused)tryPlay();};})(t);tl.appendChild(b);\n"
            + "  if(i<12){var c2=document.createElement('button');c2.textContent=lb;c2.onclick=b.onclick;ml.appendChild(c2);}}})();\n"
            + "function toggle(){if(v.ended&&cur===segs.length-1){go(0,0,true);return;}if(v.paused)tryPlay();else v.pause();}\n"
            + "function full(){if(v.requestFullscreen)v.requestFullscreen();else if(v.webkitEnterFullscreen)v.webkitEnterFullscreen();else if(v.webkitRequestFullscreen)v.webkitRequestFullscreen();else msg('이 화면에서는 전체화면을 쓸 수 없습니다.');}\n"
            + "v.addEventListener('loadedmetadata',applySeek);\n"
            + "v.addEventListener('canplay',applySeek);\n"
            + "v.addEventListener('timeupdate',showPos);\n"
            + "v.addEventListener('play',function(){$('bp').textContent='일시정지';});\n"
            + "v.addEventListener('pause',function(){$('bp').textContent='재생';});\n"
            + "v.addEventListener('ended',function(){if(cur+1<segs.length)go(cur+1,0,true);else $('bp').textContent='다시 재생';});\n"
            + "v.addEventListener('error',function(){if(gone)return;msg('영상을 불러오지 못했습니다(조각 '+(cur+1)+'). 차량 네트워크 연결을 확인해 주세요.');});\n"
            + "for(var j=0;j<segs.length;j++){var b=document.createElement('button');b.className='dot';b.setAttribute('aria-label','조각 '+(j+1));b.onclick=(function(k){return function(){go(k,0,true);};})(j);$('dots').appendChild(b);}\n"
            + "$('bb').onclick=function(){skip(-10);};\n"
            + "$('bf').onclick=function(){skip(10);};\n"
            + "$('bp').onclick=toggle;\n"
            + "$('bz').onclick=full;\n"
            + "$('bd').onclick=function(){\n"
            + " if(!confirm('이 녹화를 삭제할까요? 되돌릴 수 없습니다.'))return;\n"
            + " fetch('/sentry/delete'+(G?'?'+G.substring(1):''),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:D.id})})\n"
            + "  .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})\n"
            + "  .then(function(j){if(j.ok){gone=true;v.pause();v.removeAttribute('src');v.load();$('bd').disabled=true;$('bd').textContent='삭제됨';msg('삭제됨');}\n"
            + "   else msg('삭제하지 못했습니다'+(j.error?' ('+j.error+')':'')+'.');})\n"
            + "  .catch(function(e){msg('삭제 요청에 실패했습니다. 네트워크를 확인해 주세요.');});\n"
            + "};\n"
            + "go(0,0,D.auto);\n";

    private static final String PLAY_HTML = ""
            + "<!doctype html><html lang='ko'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
            + "<title>센트리 녹화</title><style>__CSS__</style></head><body><div class='w'>"
            + "<h1>센트리 녹화</h1><div class='sub'>__TIME__ · __DUR__ · 조각 __N__개</div>"
            + "<video id='v' playsinline webkit-playsinline controls preload='auto'></video>"
            + "<div id='pos' class='pos'>00:00 / __DUR_CLOCK__</div><div id='tl' class='tl'></div><div id='ml' class='ml'></div><div id='dots' class='dots'></div>"
            + "<div class='bar'><button id='bb'>◀ 10초</button><button id='bp'>재생</button><button id='bf'>10초 ▶</button><button id='bz'>전체화면</button></div>"
            + "<div id='msg' class='msg'></div>"
            + "<div class='info'>__SEGS__<div class='path'>__PATH__</div></div>"
            + "<button id='bd' class='del'>삭제</button>"
            + "</div><script>__JS__</script></body></html>";

    // 5분 10초 / 0분 45초 / 1시간 2분 3초 — 반올림 정수 초 기준
    static String fmtDuration(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        if (h > 0) return h + "시간 " + m + "분 " + x + "초";
        return m + "분 " + x + "초";
    }

    static String fmtClock(double seconds) {
        long s = Math.max(0, (long) Math.floor(seconds));
        long m = s / 60, x = s % 60;
        return (m < 10 ? "0" : "") + m + ":" + (x < 10 ? "0" : "") + x;
    }

    // 차량 내 경로 안내 — id는 yyyy-MM-dd_HHhmmmss 형식(검증 통과분)
    static String vehiclePath(String id) {
        return "차량 저장소 › Sentry › " + id.substring(0, 7) + " › " + id.substring(8, 10) + " › " + id;
    }

    static String buildPlayPage(SentryIndex.EventMeta meta, boolean autoplay) {
        return buildPlayPage(meta, autoplay, "");
    }

    // grantQs = 공유판 권한 값("&sgx=…&sg=…", 검증 통과분) — 페이지 안에서 만드는 영상·삭제 주소에 그대로 붙인다. 운영자판은 빈 문자열
    static String buildPlayPage(SentryIndex.EventMeta meta, boolean autoplay, String grantQs) {
        double total = 0;
        StringBuilder data = new StringBuilder("{id:'").append(meta.id).append("',auto:").append(autoplay).append(",segs:[");
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < meta.segments.size(); i++) {
            SentryIndex.Segment s = meta.segments.get(i);
            total += s.durationS;
            if (i > 0) data.append(',');
            data.append("{n:").append(s.n).append(",d:").append(s.durationS).append('}');
            rows.append("조각 ").append(i + 1).append(" · ")
                    .append(String.format(Locale.US, "%.1f", s.bytes / 1048576.0)).append("MB · ")
                    .append(fmtDuration(s.durationS)).append("<br>");
        }
        data.append("],marks:[");
        for (int i = 0; i < meta.marks.size(); i++) {
            SentryIndex.Mark k = meta.marks.get(i);
            if (i > 0) data.append(',');
            data.append('[').append(String.format(Locale.US, "%.1f", k.tMs / 1000.0)).append(',').append(k.kind).append(',').append(k.cell).append(',')
                    .append(k.pct).append(',').append(String.format(Locale.US, "%.1f", k.durMs / 1000.0)).append(']');
        }
        data.append("]}");
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date(meta.startMs)) + (meta.light ? " · 조명 번쩍임 의심" : "");
        String js = ("var G='" + (grantQs == null ? "" : grantQs) + "';\n" + PLAY_JS).replace("__DATA__", data);
        return PLAY_HTML.replace("__CSS__", CSS)
                .replace("__TIME__", time)
                .replace("__DUR_CLOCK__", fmtClock(total))
                .replace("__DUR__", fmtDuration(total))
                .replace("__N__", String.valueOf(meta.segments.size()))
                .replace("__SEGS__", rows)
                .replace("__PATH__", vehiclePath(meta.id))
                .replace("__JS__", js);
    }

    // 날짜별 목록(브라우저만으로 쓰는 용도) — 카드를 누르면 /sentry/play
    static String buildWebPage(String date, List<SentryIndex.EventMeta> events) {
        return buildWebPage(date, events, "");
    }

    static String buildWebPage(String date, List<SentryIndex.EventMeta> events, String grantQs) {
        return buildWebPage(date, events, grantQs, null);
    }

    // days = 그 달의 {일 → 이벤트 수}. null이면 달력 없이 전날/다음날만(옛 모양)
    static String buildWebPage(String date, List<SentryIndex.EventMeta> events, String grantQs, Map<Integer, Integer> days) {
        final String g = grantQs == null ? "" : grantQs;
        StringBuilder sb = new StringBuilder("<!doctype html><html lang='ko'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>센트리 녹화</title><style>")
                .append(CSS).append("</style></head><body><div class='w'><h1>센트리 녹화</h1>")
                .append("<div class='nav'><a href='/sentry/web?date=").append(shiftDate(date, -1)).append(g).append("'>◀ 전날</a><span>")
                .append(date).append("</span><a href='/sentry/web?date=").append(shiftDate(date, 1)).append(g).append("'>다음날 ▶</a></div>");
        if (days != null) sb.append(calendarHtml(date, days, g, todayKey()));
        if (events.isEmpty()) {
            sb.append("<div class='sub'>녹화 없음</div>");
        } else {
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.KOREA);
            sb.append("<div class='grid'>");
            for (SentryIndex.EventMeta e : events) {
                sb.append("<a class='c' href='/sentry/play?id=").append(e.id).append(g).append("'><img loading='lazy' src='/sentry/thumb?id=")
                        .append(e.id).append(g).append("'><div>").append(e.favorite ? "★ " : "").append(hm.format(new Date(e.startMs)))
                        .append(" · ").append(fmtDuration(e.durationS)).append("</div></a>");
            }
            sb.append("</div>");
        }
        sb.append("<div class='sub' style='margin-top:18px'><a href='/sentry/replay'>재생 시험 보고서</a> &nbsp; <a href='/sentry/bsd'>BSD 인식 비교</a></div>");
        return sb.append("</div></body></html>").toString();
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    // 월 달력 — 녹화가 있는 날만 링크(이벤트 수 표시), 선택한 날·오늘 강조, ◀▶로 달 이동(그 달 1일로). 순수 문자열 생성이라 JVM에서 시험한다.
    static String calendarHtml(String date, Map<Integer, Integer> days, String g, String today) {
        int year = Integer.parseInt(date.substring(0, 4)), month = Integer.parseInt(date.substring(5, 7)), selDay = Integer.parseInt(date.substring(8, 10));
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, 1);
        int first = c.get(Calendar.DAY_OF_WEEK) - 1;   // 0=일
        int last = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar pm = (Calendar) c.clone(), nm = (Calendar) c.clone();
        pm.add(Calendar.MONTH, -1);
        nm.add(Calendar.MONTH, 1);
        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String monthKey = String.format(Locale.US, "%04d-%02d", year, month);
        StringBuilder sb = new StringBuilder("<div class='cal'><div class='mh'><a href='/sentry/web?date=").append(ymd.format(pm.getTime())).append(g)
                .append("'>◀</a><span>").append(year).append("년 ").append(month).append("월</span><a href='/sentry/web?date=").append(ymd.format(nm.getTime())).append(g)
                .append("'>▶</a></div><table><tr>");
        for (String w : new String[]{"일", "월", "화", "수", "목", "금", "토"}) sb.append("<th>").append(w).append("</th>");
        sb.append("</tr><tr>");
        int col = 0;
        for (int i = 0; i < first; i++, col++) sb.append("<td></td>");
        for (int d = 1; d <= last; d++, col++) {
            if (col == 7) { sb.append("</tr><tr>"); col = 0; }
            String key = monthKey + "-" + String.format(Locale.US, "%02d", d);
            Integer n = days.get(d);
            StringBuilder cls = new StringBuilder();
            if (d == selDay) cls.append("sel ");
            if (key.equals(today)) cls.append("today");
            sb.append("<td").append(cls.length() > 0 ? " class='" + cls.toString().trim() + "'" : "").append(">");
            if (n != null && n > 0) sb.append("<a href='/sentry/web?date=").append(key).append(g).append("'>").append(d).append("<i>").append(n).append("</i></a>");
            else sb.append(d);
            sb.append("</td>");
        }
        for (; col < 7; col++) sb.append("<td></td>");
        return sb.append("</tr></table></div>").toString();
    }

    private static String shiftDate(String date, int days) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)));
        c.add(Calendar.DAY_OF_MONTH, days);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }
}
