package com.sysone.aiwacs.history;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sysone.aiwacs.server.AgentReport;

/**
 * 지표 이력 저장.
 * Agent는 2초마다 값을 보내지만, 그대로 DB에 쌓으면 양이 많아서 서버별로 1분씩 모아 평균 한 줄만 저장한다.
 * 보관 기간(기본 7일)이 지난 날짜의 이력은 1시간마다 지운다. 화면에서는 날짜를 골라 그날 하루(00:00~24:00)를 본다.
 * 실시간 그래프용으로 서버별 최근 40개(약 80초) 원본 값은 메모리에 따로 들고 있다 (화면을 다시 열어도 그래프가 이어지도록).
 */
@Service
public class MetricHistoryService {

    /** 1분 동안 들어온 값의 합계 (평균을 내기 위해) */
    private static final class Bucket {
        final Instant minute;
        int count;
        double cpu, memory, disk, traffic;

        Bucket(Instant minute) {
            this.minute = minute;
        }

        MetricHistory average(Long serverId) {
            return new MetricHistory(serverId, minute,
                    round1(cpu / count), round1(memory / count), round1(disk / count), round1(traffic / count));
        }
    }

    /** 실시간 그래프에 보여줄 개수 (Agent 2초 주기 × 40 = 약 80초) */
    private static final int RECENT_SIZE = 40;

    private record Point(long time, double cpu, double memory, double disk, double traffic) {}

    private final MetricHistoryRepository repository;
    /** 날짜 경계(자정)를 정하는 시간대 = 본체가 실행 중인 컴퓨터의 시간대 */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final int retentionDays;
    /** 서버 id → 아직 저장 안 된 "지금 이 1분"의 합계 */
    private final Map<Long, Bucket> current = new HashMap<>();
    /** 서버 id → 최근 원본 값 (오래된 순) */
    private final Map<Long, Deque<Point>> recent = new HashMap<>();

    public MetricHistoryService(MetricHistoryRepository repository,
                                @Value("${history.retention-days:7}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** Agent 지표가 들어올 때마다 호출. 분이 바뀌었으면 직전 1분의 평균을 저장한다. */
    public void record(Long serverId, AgentReport report) {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Bucket finished = null;
        synchronized (current) {
            Bucket b = current.get(serverId);
            if (b == null || !b.minute.equals(minute)) {
                finished = b;
                b = new Bucket(minute);
                current.put(serverId, b);
            }
            Map<String, Double> core = report.core();
            Map<String, Double> traffic = report.traffic() != null ? report.traffic() : Map.of();
            double cpu = core.getOrDefault("cpu", 0.0);
            double memory = core.getOrDefault("memory", 0.0);
            double disk = core.getOrDefault("disk", 0.0);
            double net = round1(traffic.getOrDefault("sent", 0.0) + traffic.getOrDefault("recv", 0.0));
            b.count++;
            b.cpu += cpu;
            b.memory += memory;
            b.disk += disk;
            b.traffic += net;

            Deque<Point> q = recent.computeIfAbsent(serverId, k -> new ArrayDeque<>());
            q.addLast(new Point(System.currentTimeMillis(), cpu, memory, disk, net));
            if (q.size() > RECENT_SIZE) {
                q.removeFirst();
            }
        }
        if (finished != null) {
            repository.save(finished.average(serverId));
        }
    }

    /** 매 분 0초: 지표가 끊긴 서버(오프라인)의 마지막 1분도 빠짐없이 저장 */
    @Scheduled(cron = "0 * * * * *")
    public void flush() {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        List<MetricHistory> rows = new ArrayList<>();
        synchronized (current) {
            current.entrySet().removeIf(e -> {
                if (e.getValue().minute.isBefore(minute)) {
                    rows.add(e.getValue().average(e.getKey()));
                    return true;
                }
                return false;
            });
        }
        repository.saveAll(rows);
    }

    /** 매시 정각: 보관 기간이 지난 날짜의 이력 삭제 (7일이면 오늘 포함 7일치만 남김) */
    @Scheduled(cron = "0 0 * * * *")
    public void purge() {
        repository.deleteOlderThan(oldestDay().atStartOfDay(ZONE).toInstant());
    }

    /** 볼 수 있는 날짜 목록 (오늘부터 과거 순) */
    public List<LocalDate> days() {
        LocalDate today = LocalDate.now(ZONE);
        List<LocalDate> list = new ArrayList<>();
        for (int i = 0; i < retentionDays; i++) {
            list.add(today.minusDays(i));
        }
        return list;
    }

    private LocalDate oldestDay() {
        return LocalDate.now(ZONE).minusDays(retentionDays - 1);
    }

    /**
     * 그날 하루(00:00~24:00)의 1분 평균 이력 (오래된 순).
     * 오늘이면 아직 저장 전인 "지금 이 1분"의 중간 평균도 마지막에 붙여서, 방금 켠 서버도 바로 그래프가 보이게 한다.
     */
    public List<Map<String, Object>> history(Long serverId, LocalDate date) {
        Instant from = date.atStartOfDay(ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<MetricHistory> rows = new ArrayList<>(
                repository.findByServerIdAndTimeGreaterThanEqualAndTimeLessThanOrderByTimeAsc(serverId, from, to));
        synchronized (current) {
            Bucket b = current.get(serverId);
            if (b != null && !b.minute.isBefore(from) && b.minute.isBefore(to)
                    && (rows.isEmpty() || rows.getLast().getTime().isBefore(b.minute))) {
                rows.add(b.average(serverId));
            }
        }
        return rows.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", h.getTime().toEpochMilli());
            m.put("cpu", h.getCpu());
            m.put("memory", h.getMemory());
            m.put("disk", h.getDisk());
            m.put("traffic", h.getTraffic());
            return m;
        }).toList();
    }

    /** from 이후의 1분 평균 이력 (AI 진단 구간용). 아직 저장 전인 "지금 이 1분"도 붙인다. */
    public List<MetricHistory> since(Long serverId, Instant from) {
        List<MetricHistory> rows = new ArrayList<>(repository
                .findByServerIdAndTimeGreaterThanEqualAndTimeLessThanOrderByTimeAsc(serverId, from, Instant.now().plusSeconds(60)));
        synchronized (current) {
            Bucket b = current.get(serverId);
            if (b != null && !b.minute.isBefore(from)
                    && (rows.isEmpty() || rows.getLast().getTime().isBefore(b.minute))) {
                rows.add(b.average(serverId));
            }
        }
        return rows;
    }

    /** 실시간 그래프용 최근 원본 값 (오래된 순, 최대 40개) */
    public List<Map<String, Object>> recent(Long serverId) {
        List<Point> points;
        synchronized (current) {
            points = new ArrayList<>(recent.getOrDefault(serverId, new ArrayDeque<>()));
        }
        return points.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", p.time());
            m.put("cpu", p.cpu());
            m.put("memory", p.memory());
            m.put("disk", p.disk());
            m.put("traffic", p.traffic());
            return m;
        }).toList();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
