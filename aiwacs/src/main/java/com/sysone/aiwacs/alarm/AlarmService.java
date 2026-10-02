package com.sysone.aiwacs.alarm;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sysone.aiwacs.server.MonitoredServer;
import com.sysone.aiwacs.server.ServerService;

/**
 * 알람 엔진.
 * 몇 초마다 각 서버의 판정(코드가 내린 주의/경고/위험/장애)을 확인해 알람을 생성·갱신·해제한다.
 *
 * 실제 AiWACS 부하 실측(2026-10-01)과 같게:
 * - CPU가 임계를 넘으면 CPU / CPU Core / CPU User 세 지표로 나뉘어 알람이 뜬다.
 * - 레벨마다 알람이 따로 생긴다 (장애일 때는 주의·경고·위험·장애 알람이 함께 열려 있음).
 * 그래서 한 사건이 '지표 수 × 레벨 수'만큼의 알람으로 불어난다 → events()가 코드로 사건 단위로 묶고,
 * AI는 묶인 사건의 원인·조치만 해석한다 ("집계는 코드, 해석은 AI").
 */
@Service
public class AlarmService {

    /** 자원별 표시 지표 (한 사건이 여러 알람으로 나뉘는 지점) */
    private static final Map<String, String[]> FAMILY = Map.of(
            "cpu", new String[] {"CPU", "CPU Core", "CPU User"},
            "memory", new String[] {"메모리"},
            "disk", new String[] {"디스크"});

    private static final List<String> LEVEL_ORDER = List.of("주의", "경고", "위험", "장애");
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ServerService servers;
    private final AtomicLong seq = new AtomicLong();
    /** 조건이 계속되는 중인 알람: key = serverId|metric|level (처리해도 조건이 풀릴 때까지 같은 알람의 발생 횟수가 쌓임) */
    private final Map<String, Alarm> open = new ConcurrentHashMap<>();
    /** 전체 이력(발생/해제/처리 모두, 최신순) */
    private final ConcurrentLinkedDeque<Alarm> all = new ConcurrentLinkedDeque<>();
    private static final int MAX = 500;

    public AlarmService(ServerService servers) {
        this.servers = servers;
    }

    /** 3초마다 판정을 확인해 알람을 만들고/갱신하고/해제한다 */
    @Scheduled(fixedDelay = 3000)
    public void evaluate() {
        Instant now = Instant.now();
        Set<String> stillOpen = new HashSet<>();

        for (MonitoredServer s : servers.findAll()) {
            Long id = s.getId();
            if (!servers.isOnline(id)) {
                continue;
            }
            Map<String, Map<String, Object>> judged = servers.judge(id).orElse(null);
            if (judged == null) {
                continue;
            }
            for (String resource : FAMILY.keySet()) {
                Map<String, Object> m = judged.get(resource);
                if (m == null || !(m.get("exceeded") instanceof List<?> exceeded)) {
                    continue;
                }
                double value = toDouble(m.get("value"));
                for (Object o : exceeded) {
                    if (!(o instanceof Map<?, ?> lv)) {
                        continue;
                    }
                    String level = String.valueOf(lv.get("level"));
                    int threshold = (int) toDouble(lv.get("threshold"));
                    for (String metric : FAMILY.get(resource)) {
                        String key = id + "|" + metric + "|" + level;
                        stillOpen.add(key);
                        Alarm a = open.get(key);
                        if (a == null) {
                            a = new Alarm(seq.incrementAndGet(), id, s.label(), s.getCompany(),
                                    resource, metric, level, threshold, value, now);
                            open.put(key, a);
                            all.addFirst(a);
                            while (all.size() > MAX) {
                                all.removeLast();
                            }
                        } else {
                            a.refresh(value, now);
                        }
                    }
                }
            }
        }
        // 더 이상 그 레벨 기준을 넘지 않는(값 하락·정상 복귀·오프라인) 알람은 해제
        open.entrySet().removeIf(e -> {
            if (!stillOpen.contains(e.getKey())) {
                e.getValue().resolve(now);
                return true;
            }
            return false;
        });
    }

    /** 종(알림) / 그룹핑 입력용: 현재 발생 중 알람 (최신순) */
    public List<Map<String, Object>> activeAlarms() {
        return all.stream().filter(a -> a.getStatus() == Alarm.Status.ACTIVE).map(Alarm::toMap).toList();
    }

    /** 알림 내역: 전체(발생·해제·처리) 최신순 */
    public List<Map<String, Object>> history() {
        return all.stream().map(Alarm::toMap).toList();
    }

    /** 처리 내역: 처리 기록 한 줄씩 (최신순). 같은 알람에 '조치 중 → 완료'처럼 여러 줄이 쌓인다. */
    public List<Map<String, Object>> handled() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Alarm a : all) {
            for (Alarm.ProcessRecord r : a.getProcesses()) {
                Map<String, Object> m = new LinkedHashMap<>(Alarm.recordMap(r));
                m.put("alarmId", a.getId());
                m.put("server", a.getServer());
                m.put("company", a.getCompany());
                m.put("metric", a.getMetric());
                m.put("title", a.title());
                m.put("level", a.getLevel());
                m.put("alarmStatus", a.getStatus().name());
                m.put("firstAt", Alarm.fmt(a.getFirstAt()));
                m.put("resolvedAt", Alarm.fmt(a.getResolvedAt()));
                m.put("atMillis", r.at().toEpochMilli());
                rows.add(m);
            }
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("atMillis")).reversed());
        return rows;
    }

    /** id로 알람 찾기 (AI 처리 기록 초안용) */
    public List<Alarm> byIds(List<Long> ids) {
        Set<Long> set = new HashSet<>(ids);
        return all.stream().filter(a -> set.contains(a.getId())).toList();
    }

    /**
     * 현재 발생 중 알람을 '사건' 단위로 묶는다 (AI가 아니라 코드가 묶음 — 결과가 항상 같다).
     * 같은 서버 + 같은 자원(cpu/memory/disk)이면 지표·레벨이 달라도 한 사건.
     * 예) CPU 장애 시: CPU/CPU Core/CPU User × 주의/경고/위험/장애 = 12건 → 사건 1건.
     */
    public List<Map<String, Object>> events() {
        Map<String, List<Alarm>> groups = new LinkedHashMap<>();
        all.stream()
                .filter(a -> a.getStatus() == Alarm.Status.ACTIVE)
                .forEach(a -> groups.computeIfAbsent(a.getServerId() + "|" + a.getResource(), k -> new ArrayList<>()).add(a));

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map.Entry<String, List<Alarm>> g : groups.entrySet()) {
            List<Alarm> list = g.getValue();
            Alarm first = list.get(0);
            String top = list.stream().map(Alarm::getLevel)
                    .max(Comparator.comparingInt(LEVEL_ORDER::indexOf)).orElse("주의");
            Map<String, Integer> byLevel = new LinkedHashMap<>();
            for (String lv : LEVEL_ORDER) {
                long n = list.stream().filter(a -> a.getLevel().equals(lv)).count();
                if (n > 0) {
                    byLevel.put(lv, (int) n);
                }
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("key", g.getKey());
            e.put("server", first.getServer());
            e.put("resource", first.getResource());
            e.put("level", top);
            e.put("alarmCount", list.size());
            e.put("occurrences", list.stream().mapToInt(Alarm::getCount).sum());
            e.put("byLevel", byLevel);
            e.put("metrics", list.stream().map(Alarm::getMetric).distinct().toList());
            e.put("firstAt", FMT.format(list.stream().map(Alarm::getFirstAt).min(Comparator.naturalOrder()).orElseThrow()));
            e.put("lastAt", FMT.format(list.stream().map(Alarm::getLastAt).max(Comparator.naturalOrder()).orElseThrow()));
            e.put("alarmIds", list.stream().map(Alarm::getId).toList());
            e.put("alarms", list.stream()
                    .sorted(Comparator.comparingInt((Alarm a) -> LEVEL_ORDER.indexOf(a.getLevel())).reversed())
                    .map(a -> a.getLevel() + " · " + a.title() + " · " + a.getCount() + "회")
                    .toList());
            events.add(e);
        }
        // 심각한 사건부터
        events.sort(Comparator.comparingInt((Map<String, Object> e) -> LEVEL_ORDER.indexOf(String.valueOf(e.get("level")))).reversed());
        return events;
    }

    /** 선택한 알람들에 처리 기록 추가 (발생 중이든 해제됐든 기록 가능. 발생/해제 상태는 바뀌지 않음) */
    public int handle(List<Long> ids, Alarm.ProcessStatus status, String by, String note) {
        if (ids == null || ids.isEmpty() || status == null) {
            return 0;
        }
        Set<Long> set = new HashSet<>(ids);
        Instant now = Instant.now();
        int n = 0;
        for (Alarm a : all) {
            if (set.contains(a.getId())) {
                a.process(status, by == null || by.isBlank() ? "aiwacs" : by.strip(), note, now);
                n++;
            }
        }
        return n;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
