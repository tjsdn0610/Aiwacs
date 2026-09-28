package com.sysone.aiwacs.history;

import java.time.Duration;
import java.time.Instant;
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
 * 보관 기간(기본 24시간)이 지난 이력은 1시간마다 지운다.
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
    private final Duration retention;
    /** 서버 id → 아직 저장 안 된 "지금 이 1분"의 합계 */
    private final Map<Long, Bucket> current = new HashMap<>();
    /** 서버 id → 최근 원본 값 (오래된 순) */
    private final Map<Long, Deque<Point>> recent = new HashMap<>();

    public MetricHistoryService(MetricHistoryRepository repository,
                                @Value("${history.retention-hours:24}") long retentionHours) {
        this.repository = repository;
        this.retention = Duration.ofHours(retentionHours);
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

    /** 매시 정각: 보관 기간이 지난 이력 삭제 */
    @Scheduled(cron = "0 0 * * * *")
    public void purge() {
        repository.deleteOlderThan(Instant.now().minus(retention));
    }

    /**
     * 최근 minutes분의 1분 평균 이력 (오래된 순).
     * 아직 저장 전인 "지금 이 1분"의 중간 평균도 마지막에 붙여서, 방금 켠 서버도 바로 그래프가 보이게 한다.
     */
    public List<Map<String, Object>> history(Long serverId, int minutes) {
        long max = retention.toMinutes();
        Instant from = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(Math.clamp(minutes, 1, max)));
        List<MetricHistory> rows = new ArrayList<>(
                repository.findByServerIdAndTimeGreaterThanEqualOrderByTimeAsc(serverId, from));
        synchronized (current) {
            Bucket b = current.get(serverId);
            if (b != null && (rows.isEmpty() || rows.getLast().getTime().isBefore(b.minute))) {
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
