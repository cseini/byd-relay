package com.cseini.bydsniffer.sentry;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 헤드유닛(1920x1080·터치·다크) 센트리 화면 — 링크락 런처가 WebView로 임베드한다(운영자 10-11 "런처에 센트리 화면", 톤: 배경 #0C0E11·청록 #5EC8BD).
// GET /sentry/ui(또는 /sentry/): 왼쪽 달력 + 오른쪽 썸네일 그리드, 위에 [전체|즐겨찾기]·날짜 이동. 카드를 누르면 /sentry/play?id=…&ui=1(재생, 위에 "← 목록").
// 운영자 도구 링크(재생 시험 보고서·BSD 비교)는 넣지 않는다. 순수 문자열 생성이라 JVM에서 시험한다.
final class SentryUiPage {
    private SentryUiPage() {}

    static final String BASE = "/sentry/ui";

    private static final String CSS = ""
            + "*{box-sizing:border-box}"
            + ":root{--bg:#0C0E11;--card:#151A20;--line:#232B34;--acc:#5EC8BD;--tx:#E6EDF3;--mu:#8B97A3}"
            + "body{margin:0;background:var(--bg);color:var(--tx);font-family:-apple-system,'Apple SD Gothic Neo','Noto Sans KR',sans-serif;font-size:20px;-webkit-text-size-adjust:100%}"
            + "a{color:inherit;text-decoration:none}"
            + ".top{display:flex;align-items:center;gap:20px;padding:14px 24px;border-bottom:1px solid var(--line);position:sticky;top:0;background:var(--bg);z-index:5}"
            + ".brand{font-size:26px;font-weight:700;color:var(--acc);margin-right:8px}"
            + ".tabs{display:flex;gap:8px}.tabs a{padding:12px 24px;border-radius:12px;background:var(--card);color:var(--mu);min-height:56px;display:flex;align-items:center}"
            + ".tabs a.on{background:var(--acc);color:#0C0E11;font-weight:700}"
            + ".dn{margin-left:auto;display:flex;align-items:center;gap:12px}.dn a{width:56px;height:56px;display:flex;align-items:center;justify-content:center;border-radius:12px;background:var(--card);font-size:22px}"
            + ".dn span{min-width:160px;text-align:center;font-variant-numeric:tabular-nums}"
            + ".main{display:flex;gap:24px;padding:20px 24px 32px}"
            + "aside{width:340px;flex:none}"
            + ".cal{background:var(--card);border-radius:16px;padding:14px}.cal .mh{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}"
            + ".cal .mh a{width:48px;height:48px;display:flex;align-items:center;justify-content:center;border-radius:10px;background:#1B222A}"
            + ".cal table{width:100%;border-collapse:collapse;text-align:center}.cal th{color:var(--mu);font-weight:400;font-size:16px;padding:6px 0}"
            + ".cal td{height:48px;color:#5b6773;position:relative}.cal td a{display:flex;flex-direction:column;align-items:center;justify-content:center;height:48px;color:var(--tx);border-radius:10px}"
            + ".cal td a i{font-style:normal;font-size:12px;color:var(--acc)}.cal td.sel a{background:var(--acc);color:#0C0E11;font-weight:700}.cal td.sel a i{color:#0C0E11}"
            + ".cal td.today{outline:1px solid var(--acc);border-radius:10px}"
            + ".grid{flex:1;display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:18px;align-content:start}"
            + ".c{background:var(--card);border-radius:16px;overflow:hidden;display:block}.c img{display:block;width:100%;aspect-ratio:4/3;object-fit:cover;background:#000}"
            + ".c .cap{padding:12px 14px;font-size:18px;line-height:1.4}.c .cap b{font-size:22px}.c .mu{color:var(--mu)}"
            + ".chip{display:inline-block;margin-right:6px;padding:2px 10px;border-radius:999px;background:#1B222A;color:var(--acc);font-size:15px}"
            + ".chip.l{color:var(--mu)}.fav{color:#FFC857}"
            + ".empty{color:var(--mu);padding:60px 0;text-align:center;width:100%}"
            + ".btn{padding:12px 24px;border-radius:12px;background:var(--card);color:var(--tx);min-height:56px;display:flex;align-items:center;cursor:pointer;user-select:none}"
            + ".c{position:relative}.ck{display:none;position:absolute;top:12px;left:12px;width:36px;height:36px;border-radius:50%;border:3px solid #fff;background:rgba(0,0,0,.45);z-index:2}"
            + "body.sel .ck{display:block}.c.chk .ck{background:var(--acc);border-color:var(--acc)}.c.chk .ck:after{content:'✓';display:block;text-align:center;line-height:30px;color:#0C0E11;font-weight:700;font-size:24px}"
            + "body.sel .c.chk{outline:4px solid var(--acc)}"
            + ".selbar{display:none;position:fixed;left:0;right:0;bottom:0;padding:14px 24px;background:#10151b;border-top:1px solid var(--line);align-items:center;gap:14px;z-index:9}"
            + "body.sel .selbar{display:flex}.selbar .n{margin-right:auto;font-size:22px}.selbar .del{background:#E5534B;color:#fff;font-weight:700}.selbar .del.off{opacity:.4;pointer-events:none}"
            + "body.sel{padding-bottom:96px}";

    // 여러 개 삭제용 스크립트(운영자 10-11 "목록에서 여러 개 선택 후 삭제") — 선택 모드에서 카드를 누르면 체크, 삭제는 /sentry/delete {"ids":[…]}를 100개씩 나눠 보낸다. G=공유판 권한 값
    private static String script(String g, boolean favTab) {
        return "var G=" + jsStr(g) + ",FAVTAB=" + favTab + ",sel={},on=false;function $(i){return document.getElementById(i)}\n"
                + "function cards(){return document.querySelectorAll('.c')}\n"
                + "function upd(){var n=Object.keys(sel).length;$('sn').textContent=n+'개 선택';var b=$('bdel');b.className='btn del'+(n?'':' off');}\n"
                + "function mode(v){on=v;document.body.className=v?'sel':'';sel={};var cs=cards();for(var i=0;i<cs.length;i++)cs[i].classList.remove('chk');$('bsel').textContent=v?'선택 끝':'선택';upd();}\n"
                + "(function(){var cs=cards();for(var i=0;i<cs.length;i++)(function(a){a.addEventListener('click',function(e){if(!on)return;e.preventDefault();var id=a.getAttribute('data-id');if(sel[id]){delete sel[id];a.classList.remove('chk')}else{sel[id]=1;a.classList.add('chk')}upd();})})(cs[i]);})();\n"
                + "$('bsel').onclick=function(){mode(!on)};$('bcancel').onclick=function(){mode(false)};\n"
                + "$('ball').onclick=function(){var cs=cards();for(var i=0;i<cs.length;i++){sel[cs[i].getAttribute('data-id')]=1;cs[i].classList.add('chk')}upd();};\n"
                + "function post(ids){return fetch('/sentry/delete'+(G?'?'+G.substring(1):''),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ids:ids})}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();});}\n"
                + "function favPost(ids,v){return fetch('/sentry/fav'+(G?'?'+G.substring(1):''),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ids:ids,favorite:v})}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();});}\n"
                + "function setFav(v){var ids=Object.keys(sel);if(!ids.length)return;var bad=[];\n"
                + " (function next(k){if(k>=ids.length){done();return;}var part=ids.slice(k,k+100);favPost(part,v).then(function(j){var up=j.updated||[];\n"
                + "   for(var x=0;x<up.length;x++){var el=document.querySelector('.c[data-id=\"'+up[x]+'\"]');if(!el)continue;el.setAttribute('data-fav',v?'1':'0');var cap=el.querySelector('.cap');var st=cap&&cap.querySelector('.fav');\n"
                + "    if(v&&cap&&!st){var s=document.createElement('span');s.className='fav';s.textContent='★';cap.insertBefore(document.createTextNode(' '),cap.firstChild);cap.insertBefore(s,cap.firstChild);}else if(!v&&st){st.parentNode.removeChild(st);}\n"
                + "    if(!v&&FAVTAB&&el.parentNode)el.parentNode.removeChild(el);}\n"
                + "   for(var y=0;y<(j.failed||[]).length;y++)bad.push(j.failed[y].id+' '+j.failed[y].error);next(k+100);}).catch(function(e){bad.push('요청 실패 '+e.message);done();});})(0);\n"
                + " function done(){mode(false);if(bad.length)alert('일부를 바꾸지 못했어요 ('+bad.length+'개)\\n'+bad.slice(0,5).join('\\n'));else if(!cards().length)location.reload();}\n"
                + "}\n"
                + "$('bfav').onclick=function(){setFav(true)};$('bunfav').onclick=function(){setFav(false)};\n"
                + "$('bdel').onclick=function(){var ids=Object.keys(sel);if(!ids.length)return;if(!confirm(ids.length+'개 녹화를 삭제할까요? 되돌릴 수 없습니다.'))return;\n"
                + " var chunks=[];for(var i=0;i<ids.length;i+=100)chunks.push(ids.slice(i,i+100));var bad=[],okn=0;\n"
                + " (function next(k){if(k>=chunks.length){fin();return;}post(chunks[k]).then(function(j){var gone=(j.deleted||[]).concat(j.gone||[]);okn+=(j.deleted||[]).length;\n"
                + "   for(var x=0;x<gone.length;x++){var el=document.querySelector('.c[data-id=\"'+gone[x]+'\"]');if(el&&el.parentNode)el.parentNode.removeChild(el);}\n"
                + "   for(var y=0;y<(j.failed||[]).length;y++)bad.push(j.failed[y].id+' '+j.failed[y].error);next(k+1);}).catch(function(e){bad.push('요청 실패 '+e.message);fin();});})(0);\n"
                + " function fin(){mode(false);if(bad.length)alert('일부를 지우지 못했어요 ('+bad.length+'개)\\n'+bad.slice(0,5).join('\\n'));else if(!cards().length)location.reload();}\n"
                + "};";
    }

    private static String jsStr(String s) {
        return "'" + (s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'")) + "'";
    }

    // date=yyyy-MM-dd, g=공유판 권한 값("&sgx=…&sg=…", 없으면 ""), days=그 달 날짜별 이벤트 수. favOnly면 events는 날짜가 섞인 즐겨찾기 목록
    static String build(String date, List<SentryIndex.EventMeta> events, String g, Map<Integer, Integer> days, boolean favOnly, String today) {
        String gq = g == null ? "" : g;
        StringBuilder sb = new StringBuilder("<!doctype html><html lang='ko'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>센트리</title><style>")
                .append(CSS).append("</style></head><body><div class='top'><div class='brand'>센트리</div><div class='tabs'>")
                .append("<a class='").append(favOnly ? "" : "on").append("' href='").append(BASE).append("?date=").append(date).append(gq).append("'>전체</a>")
                .append("<a class='").append(favOnly ? "on" : "").append("' href='").append(BASE).append("?fav=1&date=").append(date).append(gq).append("'>즐겨찾기</a><a href='").append(BASE).append("?tab=storage").append(gq).append("'>저장소</a></div>")
                .append("<a id='bsel' class='btn'>선택</a>");
        if (!favOnly) {
            sb.append("<div class='dn'><a href='").append(BASE).append("?date=").append(shiftDate(date, -1)).append(gq).append("'>◀</a><span>").append(date)
                    .append("</span><a href='").append(BASE).append("?date=").append(shiftDate(date, 1)).append(gq).append("'>▶</a></div>");
        }
        sb.append("</div><div class='main'>");
        if (!favOnly) sb.append("<aside>").append(calendar(date, days == null ? java.util.Collections.<Integer, Integer>emptyMap() : days, gq, today)).append("</aside>");
        if (events.isEmpty()) {
            sb.append("<div class='empty'>").append(favOnly ? "즐겨찾기한 녹화가 없어요" : "이 날은 녹화가 없어요").append("</div>");
        } else {
            SimpleDateFormat hm = new SimpleDateFormat(favOnly ? "M/d HH:mm" : "HH:mm", Locale.KOREA);
            sb.append("<div class='grid'>");
            for (SentryIndex.EventMeta e : events) {
                sb.append("<a class='c' data-id='").append(e.id).append("' data-fav='").append(e.favorite ? 1 : 0).append("' href='/sentry/play?id=").append(e.id).append("&ui=1").append(gq).append("'><i class='ck'></i><img loading='lazy' src='/sentry/thumb?id=").append(e.id).append(gq)
                        .append("'><div class='cap'>").append(e.favorite ? "<span class='fav'>★</span> " : "").append("<b>").append(hm.format(new Date(e.startMs))).append("</b> <span class='mu'>· ")
                        .append(dur(e.durationS)).append("</span><br>").append(chips(e)).append("</div></a>");
            }
            sb.append("</div>");
        }
        sb.append("</div><div style='padding:0 24px 28px'>").append(SentrySource.NOTE).append("</div>");   // 소스 주소 안내는 .main(가로 배치) 밖에 둔다
        sb.append("<div class='selbar'><span class='n' id='sn'>0개 선택</span><a id='ball' class='btn'>모두 선택</a><a id='bfav' class='btn'>즐겨찾기 추가</a><a id='bunfav' class='btn'>즐겨찾기 해제</a><a id='bdel' class='btn del off'>삭제</a><a id='bcancel' class='btn'>취소</a></div>");
        return sb.append("<script>").append(script(gq, favOnly)).append("</script></body></html>").toString();
    }

    // 저장소 탭(운영자 10-11 "usb나 sd는 포맷 기능도") — 볼륨 목록과 microSD·USB의 [포맷] 버튼. 포맷은 /sentry/format {kind,id,confirm:"format"} 뒤 /sentry/status의 format.state로 끝날 때까지 기다린다.
    //   확인창을 두 번 거친다(전부 지워짐 경고). 시동이 켜져 있으면 서버가 거절하고 그 사유를 그대로 보여 준다
    static String buildStorage(String g) {
        String gq = g == null ? "" : g;
        StringBuilder sb = new StringBuilder("<!doctype html><html lang='ko'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>센트리</title><style>")
                .append(CSS).append(".vol{background:var(--card);border-radius:16px;padding:18px 22px;margin:0 0 16px;display:flex;align-items:center;gap:18px;max-width:900px}.vol .t{flex:1;line-height:1.5}.vol .t b{font-size:24px}")
                .append(".vol .fmt{background:#E5534B;color:#fff;font-weight:700}.vol .fmt.off{opacity:.4;pointer-events:none}#msg{padding:0 24px 12px;font-size:20px;color:var(--acc);min-height:30px}")
                .append("</style></head><body><div class='top'><div class='brand'>센트리</div><div class='tabs'>")
                .append("<a href='").append(BASE).append(gq.isEmpty() ? "" : "?" + gq.substring(1)).append("'>전체</a>")
                .append("<a href='").append(BASE).append("?fav=1").append(gq).append("'>즐겨찾기</a><a class='on' href='").append(BASE).append("?tab=storage").append(gq).append("'>저장소</a></div></div>")
                .append("<div id='msg'></div><div class='main' style='display:block'><div id='vols'></div></div>")
                .append("<div style='padding:0 24px 28px'>").append(SentrySource.NOTE).append("</div><script>")
                .append("var G=").append(jsStr(gq)).append(";function $(i){return document.getElementById(i)}var busy=false;\n")
                .append("function q(u){return u+(G?(u.indexOf('?')<0?'?':'&')+G.substring(1):'')}\n")
                .append("function gb(mb){return mb>=1024?(mb/1024).toFixed(1)+' GB':mb+' MB'}\n")
                .append("function msg(t){$('msg').textContent=t}\n")
                .append("function load(){fetch(q('/sentry/storage')).then(function(r){return r.json()}).then(function(j){var h='',v=j.volumes||[];for(var i=0;i<v.length;i++){var x=v[i];var rem=x.kind==='sd'||x.kind==='usb';\n")
                .append("  h+=\"<div class='vol'><div class='t'><b>\"+x.label+\"</b><br>\"+(x.mounted?'전체 '+gb(x.totalMb)+' · 남음 '+gb(x.freeMb)+' · 센트리 '+gb(x.usedBySentryMb):'연결 안 됨')+\"</div>\";\n")
                .append("  if(rem&&x.mounted)h+=\"<a class='btn fmt\"+(busy?' off':'')+\"' data-k='\"+x.kind+\"' data-id='\"+x.id+\"' data-l='\"+x.label+\"'>포맷</a>\";h+='</div>';}\n")
                .append("  $('vols').innerHTML=h;var bs=document.querySelectorAll('.fmt');for(var k=0;k<bs.length;k++)bs[k].onclick=function(){ask(this)};}).catch(function(e){msg('저장소 정보를 못 읽었어요: '+e)})}\n")
                .append("function ask(a){if(busy)return;var l=a.getAttribute('data-l');\n")
                .append(" if(!confirm(l+' 의 모든 파일이 지워집니다(센트리 녹화뿐 아니라 음악 등 전부). 되돌릴 수 없어요. 계속할까요?'))return;\n")
                .append(" if(!confirm('정말 '+l+' 를 포맷할까요? 주차 중에만 됩니다(씨라7은 P단 정차 중).'))return;\n")
                .append(" busy=true;msg('포맷 요청 중…');fetch(q('/sentry/format'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({kind:a.getAttribute('data-k'),id:a.getAttribute('data-id'),confirm:'format'})})\n")
                .append(" .then(function(r){return r.json()}).then(function(j){if(!j.ok){busy=false;msg('포맷 못 함: '+(j.error||'알 수 없음'));load();return;}msg('포맷 중… 카드 크기에 따라 1분 넘게 걸릴 수 있어요');poll(0)}).catch(function(e){busy=false;msg('요청 실패: '+e)})}\n")
                .append("function poll(n){fetch(q('/sentry/status')).then(function(r){return r.json()}).then(function(s){var f=s.format;if(f&&f.state==='running'&&n<120){setTimeout(function(){poll(n+1)},3000);return;}\n")
                .append("  busy=false;if(f&&f.state==='done')msg('포맷이 끝났어요');else if(f&&f.state==='failed')msg('포맷 실패: '+(f.why||''));else msg('결과를 확인하지 못했어요 — 저장소 목록을 보세요');load();}).catch(function(){if(n<120)setTimeout(function(){poll(n+1)},3000);else{busy=false;msg('결과를 확인하지 못했어요')}})}\n")
                .append("load();</script></body></html>");
        return sb.toString();
    }

    // 재생 화면(/sentry/play?…&ui=1)을 런처용으로 — 맨 위에 "← 목록" 링크와 넓은 화면용 스타일을 덧붙인다(재생 화면 코드는 건드리지 않는다)
    static String decoratePlay(String playHtml, String id, String g) {
        String date = id.substring(0, 10);
        String back = "<a class='uiback' href='" + BASE + "?date=" + date + (g == null ? "" : g) + "'>← 목록</a>";
        String css = ".uiback{display:inline-flex;align-items:center;min-height:56px;padding:0 22px;margin:4px 0 6px;border-radius:12px;background:#151A20;color:#5EC8BD;font-size:20px;text-decoration:none}"
                + "body{background:#0C0E11}.w{max-width:1320px}button{min-height:52px;font-size:18px}";
        String out = playHtml.replaceFirst("</style>", java.util.regex.Matcher.quoteReplacement(css + "</style>"));
        return out.replaceFirst("<div class='w'>", java.util.regex.Matcher.quoteReplacement("<div class='w'>" + back));
    }

    private static String chips(SentryIndex.EventMeta e) {
        int kind = 0;
        for (float[] o : e.objects) if (o != null && o.length > 0) kind = Math.max(kind, (int) o[0]);   // 2 사람이 1 차량보다 우선
        StringBuilder s = new StringBuilder();
        if (kind == 2) s.append("<span class='chip'>사람</span>");
        else if (kind == 1) s.append("<span class='chip'>차량</span>");
        if (e.light && kind == 0) s.append("<span class='chip l'>조명 의심</span>");
        return s.toString();
    }

    private static String dur(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        if (h > 0) return h + "시간 " + m + "분";
        return m > 0 ? m + "분 " + x + "초" : x + "초";
    }

    static String shiftDate(String date, int days) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar c = Calendar.getInstance();
            c.setTime(f.parse(date));
            c.add(Calendar.DAY_OF_MONTH, days);
            return f.format(c.getTime());
        } catch (java.text.ParseException e) {
            return date;
        }
    }

    // 월 달력 — 녹화가 있는 날만 링크(이벤트 수 표시), 선택한 날·오늘 강조, ◀▶로 달 이동(그 달 1일로)
    static String calendar(String date, Map<Integer, Integer> days, String g, String today) {
        int year = Integer.parseInt(date.substring(0, 4)), month = Integer.parseInt(date.substring(5, 7)), selDay = Integer.parseInt(date.substring(8, 10));
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, 1);
        int first = c.get(Calendar.DAY_OF_WEEK) - 1;
        int last = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar pm = (Calendar) c.clone(), nm = (Calendar) c.clone();
        pm.add(Calendar.MONTH, -1);
        nm.add(Calendar.MONTH, 1);
        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String monthKey = String.format(Locale.US, "%04d-%02d", year, month);
        StringBuilder sb = new StringBuilder("<div class='cal'><div class='mh'><a href='" + BASE + "?date=").append(ymd.format(pm.getTime())).append(g)
                .append("'>◀</a><span>").append(year).append("년 ").append(month).append("월</span><a href='" + BASE + "?date=").append(ymd.format(nm.getTime())).append(g)
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
            if (n != null && n > 0) sb.append("<a href='" + BASE + "?date=").append(key).append(g).append("'>").append(d).append("<i>").append(n).append("</i></a>");
            else sb.append(d);
            sb.append("</td>");
        }
        for (; col < 7; col++) sb.append("<td></td>");
        return sb.append("</tr></table></div>").toString();
    }
}
