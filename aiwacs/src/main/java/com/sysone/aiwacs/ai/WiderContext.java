package com.sysone.aiwacs.ai;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.sysone.aiwacs.alarm.Alarm;
import com.sysone.aiwacs.alarm.AlarmService;
import com.sysone.aiwacs.alarm.AlarmStore;
import com.sysone.aiwacs.history.MetricHistory;
import com.sysone.aiwacs.history.MetricHistoryService;
import com.sysone.aiwacs.history.ProcessHistory;
import com.sysone.aiwacs.history.ProcessHistoryService;
import com.sysone.aiwacs.history.ProcessTrend;

/**
 * 2차 진단(과거 데이터까지)에 쓰는 근거를 코드가 계산한다. AI는 이 결과를 해석만 한다.
 *
 * ① 같은 시간대 다른 서버 이벤트 — 이 서버보다 먼저/함께 시작한 이벤트와, 그 서버에서 새로 등장한 프로세스
 * ② 평소 대비 — 지난 7일 같은 시간대의 지표 평균, 원인 후보 프로세스가 평소 언제 돌았는지
 * ③ 과거 비슷한 이벤트 — 같은 서버·자원에서 예전에 생긴 이벤트의 처리 기록
 * → 이 세 가지로 '근본 원인 후보'(다른 서버에서 먼저 시작했고 새로 등장한 프로세스가 있는 이벤트)를 정해진 규칙으로 고른다
 */
@Component
public class WiderContext {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int BASE_DAYS = 7;
    /** 이 시간 안에 시작한 다른 서버 이벤트를 '같은 시간대'로 본다 */
    private static final Duration NEAR = Duration.ofMinutes(15);
    /** 과거 알람끼리 이 간격 안이면 한 이벤트 */
    private static final Duration PAST_GAP = Duration.ofMinutes(30);
    private static final Map<String, String> RES_KR = Map.of("cpu", "CPU", "memory", "메모리", "disk", "디스크");

    private final AlarmService alarms;
    private final AlarmStore store;
    private final MetricHistoryService metrics;
    private final ProcessHistoryService processes;

    public WiderContext(AlarmService alarms, AlarmStore store, MetricHistoryService metrics, ProcessHistoryService processes) {
        this.alarms = alarms;
        this.store = store;
        this.metrics = metrics;
        this.processes = processes;
    }

    /** 이 서버의 대표 이벤트 (지금 발생 중이거나 처리가 끝나지 않은 이벤트 중 가장 심각한 것) */
    public Map<String, Object> mainEvent(Long serverId) {
        return alarms.events(true).stream()
                .filter(e -> serverId.equals(e.get("serverId")))
                .findFirst().orElse(null);
    }

    /**
     * @param serverId 진단 중인 서버
     * @param from     진단 구간 시작
     * @param suspects 이 서버의 원인 후보 (코드 계산)
     */
    public Map<String, Object> build(Long serverId, String serverName, Instant from, List<ProcessTrend.Suspect> suspects) {
        Instant now = Instant.now();
        Map<String, Object> main = mainEvent(serverId);
        Instant myStart = main != null ? Instant.ofEpochMilli(((Number) main.get("firstAtMillis")).longValue()) : from;

        Map<String, Object> ctx = new LinkedHashMap<>();
        // ① 같은 시간대 다른 서버 이벤트 (시작 순)
        List<Map<String, Object>> others = new ArrayList<>();
        Map<Long, List<ProcessTrend.Suspect>> otherSuspects = new LinkedHashMap<>();
        for (Map<String, Object> e : alarms.events(true)) {
            Long sid = (Long) e.get("serverId");
            Instant st = Instant.ofEpochMilli(((Number) e.get("firstAtMillis")).longValue());
            if (sid.equals(serverId) || st.isBefore(myStart.minus(NEAR)) || st.isAfter(myStart.plus(NEAR))) {
                continue;
            }
            List<ProcessTrend.Suspect> sus = otherSuspects.computeIfAbsent(sid,
                    k -> ProcessTrend.analyze(processes.since(k, from.minus(Duration.ofMinutes(5))), 3));
            long diffMin = Duration.between(st, myStart).toSeconds() / 60;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serverId", sid);
            m.put("server", e.get("server"));
            m.put("resource", RES_KR.getOrDefault(String.valueOf(e.get("resource")), String.valueOf(e.get("resource"))));
            m.put("level", e.get("level"));
            m.put("startedAt", HM.format(st));
            m.put("since", st.toEpochMilli());
            m.put("relation", diffMin > 0 ? "이 서버보다 " + diffMin + "분 먼저 시작" : diffMin < 0 ? "이 서버보다 " + (-diffMin) + "분 늦게 시작" : "같은 시각에 시작");
            m.put("newProcesses", sus.stream().filter(x -> "NEW".equals(x.type()))
                    .map(x -> x.name() + " (" + x.firstSeen() + "부터, CPU 최고 " + x.peakCpu() + "% · 메모리 " + x.peakMem() + "%)").toList());
            others.add(m);
        }
        others.sort(Comparator.comparing(m -> (Long) m.get("since")));
        ctx.put("thisStartedAt", HM.format(myStart));
        ctx.put("otherEvents", others);

        // ② 평소 대비 — 이 서버 + 이벤트가 있는 다른 서버
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(serverId);
        others.forEach(o -> ids.add((Long) o.get("serverId")));
        List<Map<String, Object>> baseline = new ArrayList<>();
        for (Long sid : ids) {
            Map<String, Object> b = metricBaseline(sid, from, now);
            if (b != null) {
                b.put("server", sid.equals(serverId) ? serverName : others.stream().filter(o -> sid.equals(o.get("serverId"))).findFirst().map(o -> o.get("server")).orElse(""));
                baseline.add(b);
            }
        }
        ctx.put("baseline", baseline);
        List<Map<String, Object>> usual = new ArrayList<>();
        suspects.stream().limit(3).forEach(s -> usual.add(processUsual(serverId, serverName, s.name(), from)));
        otherSuspects.forEach((sid, list) -> list.stream().filter(x -> "NEW".equals(x.type())).limit(2).forEach(s -> usual.add(processUsual(sid,
                String.valueOf(others.stream().filter(o -> sid.equals(o.get("serverId"))).findFirst().map(o -> o.get("server")).orElse("")), s.name(), from))));
        ctx.put("processUsual", usual);

        // ③ 과거 비슷한 이벤트의 처리 기록 — 이 서버 이벤트 + 같은 시간대 다른 서버 이벤트
        List<Map<String, Object>> past = new ArrayList<>();
        if (main != null) {
            past.addAll(pastIncidents(serverId, String.valueOf(main.get("resource")), myStart));
        }
        for (Map<String, Object> o : others) {
            String res = RES_KR.entrySet().stream().filter(x -> x.getValue().equals(o.get("resource"))).map(Map.Entry::getKey).findFirst().orElse("");
            past.addAll(pastIncidents((Long) o.get("serverId"), res, Instant.ofEpochMilli((Long) o.get("since"))));
        }
        // 같은 처리 기록이 두 번 들어가지 않게
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        past.forEach(p -> uniq.putIfAbsent(p.get("server") + "|" + p.get("when") + "|" + p.get("note"), p));
        List<Map<String, Object>> pastList = new ArrayList<>(uniq.values()).subList(0, Math.min(4, uniq.size()));
        ctx.put("pastIncidents", pastList);
        ctx.put("rootCandidates", rootCandidates(myStart, others, usual, pastList));
        return ctx;
    }

    /**
     * 근본 원인 후보 (정해진 규칙): 이 서버보다 먼저(또는 같은 분에) 이벤트가 시작된 다른 서버 중, 그 서버에 새로 등장한 프로세스가 있는 것.
     * 근거로 ① 시작 순서 ② 평소와 다른 시간에 돈 작업인지 ③ 과거 처리 기록에 같은 프로세스가 나오는지를 붙인다. 먼저 시작한 순.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rootCandidates(Instant myStart, List<Map<String, Object>> others,
                                                            List<Map<String, Object>> usual, List<Map<String, Object>> past) {
        Map<Long, Map<String, Object>> byServer = new LinkedHashMap<>();
        for (Map<String, Object> o : others) { // 시작 순으로 정렬돼 있음
            List<String> news = (List<String>) o.get("newProcesses");
            long since = (Long) o.get("since");
            if (news.isEmpty() || since > myStart.plus(Duration.ofMinutes(1)).toEpochMilli() || byServer.containsKey((Long) o.get("serverId"))) {
                continue;
            }
            String proc = news.get(0).split(" ")[0];
            List<String> why = new ArrayList<>();
            why.add(o.get("server") + " 이벤트가 " + o.get("relation").toString().replace("이 서버보다 ", "이 서버보다 "));
            why.add(proc + "이(가) 이벤트 직전에 새로 등장");
            usual.stream().filter(u -> proc.equals(u.get("process")) && String.valueOf(u.get("usual")).contains("주로"))
                    .findFirst().ifPresent(u -> why.add("평소에는 " + String.valueOf(u.get("usual")).replaceAll("^지난 \\d+일 중 \\d+일, ", "").replaceAll(", 평소 CPU.*$", "") + " → 오늘은 평소와 다른 시간에 실행"));
            past.stream().filter(p -> String.valueOf(p.get("note")).contains(proc)).findFirst()
                    .ifPresent(p -> why.add(p.get("when") + " 같은 상황의 처리 기록 있음"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("serverId", o.get("serverId"));
            c.put("server", o.get("server"));
            c.put("since", since);
            c.put("process", proc);
            c.put("reasons", why);
            byServer.put((Long) o.get("serverId"), c);
        }
        return new ArrayList<>(byServer.values());
    }

    /** 지금 구간 vs 지난 7일 같은 시간대 (CPU·메모리·디스크 평균) */
    private Map<String, Object> metricBaseline(Long serverId, Instant from, Instant now) {
        List<MetricHistory> nowRows = metrics.since(serverId, from);
        if (nowRows.isEmpty()) {
            return null;
        }
        double[] cur = avg(nowRows);
        double[] peak = new double[3];
        nowRows.forEach(h -> { peak[0] = Math.max(peak[0], h.getCpu()); peak[1] = Math.max(peak[1], h.getMemory()); peak[2] = Math.max(peak[2], h.getDisk()); });
        List<MetricHistory> past = new ArrayList<>();
        int days = 0;
        for (int d = 1; d <= BASE_DAYS; d++) {
            List<MetricHistory> rows = metrics.between(serverId, from.minus(d, ChronoUnit.DAYS), now.minus(d, ChronoUnit.DAYS));
            if (!rows.isEmpty()) {
                past.addAll(rows);
                days++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("window", HM.format(from) + "~" + HM.format(now));
        m.put("nowAvg", Map.of("cpu", r1(cur[0]), "memory", r1(cur[1]), "disk", r1(cur[2])));
        m.put("nowPeak", Map.of("cpu", r1(peak[0]), "memory", r1(peak[1]), "disk", r1(peak[2])));
        if (days == 0) {
            m.put("usualAvg", null);
            m.put("days", 0);
        } else {
            double[] u = avg(past);
            m.put("usualAvg", Map.of("cpu", r1(u[0]), "memory", r1(u[1]), "disk", r1(u[2])));
            m.put("days", days);
        }
        return m;
    }

    /** 이 프로세스가 지난 7일 동안 언제 돌았는지 */
    private Map<String, Object> processUsual(Long serverId, String server, String name, Instant from) {
        List<ProcessHistory> rows = processes.ofProcess(serverId, name, from.minus(BASE_DAYS, ChronoUnit.DAYS), from.minus(Duration.ofMinutes(30)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("server", server);
        m.put("process", name);
        if (rows.isEmpty()) {
            m.put("usual", "지난 " + BASE_DAYS + "일 동안 기록 없음 (평소에 없던 프로세스)");
            return m;
        }
        Set<String> days = new TreeSet<>();
        Map<String, int[]> hours = new LinkedHashMap<>(); // 시작 시각대(시) → 횟수
        rows.forEach(r -> {
            days.add(DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneId.systemDefault()).format(r.getTime()));
            hours.computeIfAbsent(DateTimeFormatter.ofPattern("HH").withZone(ZoneId.systemDefault()).format(r.getTime()), k -> new int[1])[0]++;
        });
        boolean allDay = hours.size() >= 18;
        String when;
        if (allDay) {
            when = "하루 종일 실행 (업무 프로세스)";
        } else {
            List<String> hs = hours.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(2).map(e -> e.getKey() + "시").sorted().toList();
            when = "주로 " + String.join("·", hs) + "대에 실행";
        }
        double avgCpu = rows.stream().mapToDouble(ProcessHistory::getCpu).average().orElse(0);
        m.put("usual", "지난 " + BASE_DAYS + "일 중 " + Math.min(BASE_DAYS, days.size()) + "일, " + when + ", 평소 CPU 평균 " + r1(avgCpu) + "%");
        m.put("nowHour", HM.format(from));
        return m;
    }

    /** 같은 서버·자원의 과거 이벤트 중 처리 기록이 있는 것 (최근 것부터 최대 2건) */
    private List<Map<String, Object>> pastIncidents(Long serverId, String resource, Instant before) {
        List<Alarm> list = store.findPast(serverId, resource, before.minus(Duration.ofHours(1)));
        List<Map<String, Object>> out = new ArrayList<>();
        Instant clusterEnd = null;
        Map<String, Object> cur = null;
        for (Alarm a : list) { // 최신순
            if (cur == null || a.getFirstAt().isBefore(clusterEnd.minus(PAST_GAP))) {
                if (cur != null && cur.get("note") != null) {
                    out.add(cur);
                }
                if (out.size() >= 2) {
                    break;
                }
                cur = new LinkedHashMap<>();
                cur.put("server", a.getServer());
                cur.put("resource", RES_KR.getOrDefault(resource, resource));
                cur.put("when", MDHM.format(a.getFirstAt()));
                cur.put("level", a.getLevel());
            }
            clusterEnd = a.getFirstAt();
            for (Alarm.ProcessRecord r : a.getProcesses()) {
                if (r.note() == null || r.note().isBlank()) {
                    continue;
                }
                // 완료 기록을 우선 (이벤트의 결론), 없으면 다른 기록
                if (cur.get("note") == null || r.status() == Alarm.ProcessStatus.COMPLETE) {
                    cur.put("note", r.note());
                    cur.put("status", r.status().kr);
                }
            }
        }
        if (cur != null && cur.get("note") != null && out.size() < 2) {
            out.add(cur);
        }
        return out;
    }

    private static double[] avg(List<MetricHistory> rows) {
        double c = 0, m = 0, d = 0;
        for (MetricHistory h : rows) {
            c += h.getCpu();
            m += h.getMemory();
            d += h.getDisk();
        }
        int n = Math.max(1, rows.size());
        return new double[] {c / n, m / n, d / n};
    }

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
