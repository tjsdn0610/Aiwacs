package com.sysone.aiwacs.history;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sysone.aiwacs.server.AgentReport;

/**
 * 프로세스 이력 저장 (MetricHistoryService와 같은 방식).
 * Agent가 2초마다 보내는 프로세스 목록을 서버별로 1분씩 모아, CPU 상위 8개 + 메모리 상위 5개만 저장한다.
 * 보관 기간도 지표 이력과 같다(history.retention-days).
 */
@Service
public class ProcessHistoryService {

    private static final int TOP_CPU = 8;
    private static final int TOP_MEM = 5;

    private static final class Acc {
        final String name;
        final Long pid;
        final Long started;
        double cpuSum;
        double memMax;

        Acc(String name, Long pid, Long started) {
            this.name = name;
            this.pid = pid;
            this.started = started;
        }
    }

    private static final class Bucket {
        final Instant minute;
        int samples;
        final Map<String, Acc> procs = new HashMap<>();

        Bucket(Instant minute) {
            this.minute = minute;
        }

        List<ProcessHistory> rows(Long serverId) {
            int n = Math.max(1, samples);
            List<Acc> all = new ArrayList<>(procs.values());
            List<Acc> keep = new ArrayList<>(all.stream()
                    .sorted(Comparator.comparingDouble((Acc a) -> a.cpuSum).reversed()).limit(TOP_CPU).toList());
            all.stream().sorted(Comparator.comparingDouble((Acc a) -> a.memMax).reversed()).limit(TOP_MEM)
                    .filter(a -> !keep.contains(a)).forEach(keep::add);
            return keep.stream()
                    .map(a -> new ProcessHistory(serverId, minute, a.name, a.pid, a.started, round1(a.cpuSum / n), a.memMax))
                    .toList();
        }
    }

    private final ProcessHistoryRepository repository;
    private final int retentionDays;
    private final Map<Long, Bucket> current = new HashMap<>();

    public ProcessHistoryService(ProcessHistoryRepository repository,
                                 @Value("${history.retention-days:7}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** Agent 지표가 들어올 때마다 호출. 분이 바뀌었으면 직전 1분을 저장한다. */
    public void record(Long serverId, AgentReport report) {
        if (report.procs() == null) {
            return;
        }
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Bucket finished = null;
        synchronized (current) {
            Bucket b = current.get(serverId);
            if (b == null || !b.minute.equals(minute)) {
                finished = b;
                b = new Bucket(minute);
                current.put(serverId, b);
            }
            b.samples++;
            for (AgentReport.Proc p : report.procs()) {
                if (p.name() == null) {
                    continue;
                }
                Acc a = b.procs.computeIfAbsent(p.name() + "|" + p.pid(), k -> new Acc(p.name(), p.pid(), p.start()));
                a.cpuSum += p.cpu();
                a.memMax = Math.max(a.memMax, p.mem());
            }
        }
        if (finished != null) {
            repository.saveAll(finished.rows(serverId));
        }
    }

    /** 매 분 0초: 지표가 끊긴 서버의 마지막 1분도 저장 */
    @Scheduled(cron = "0 * * * * *")
    public void flush() {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        List<ProcessHistory> rows = new ArrayList<>();
        synchronized (current) {
            current.entrySet().removeIf(e -> {
                if (e.getValue().minute.isBefore(minute)) {
                    rows.addAll(e.getValue().rows(e.getKey()));
                    return true;
                }
                return false;
            });
        }
        repository.saveAll(rows);
    }

    @Scheduled(cron = "30 0 * * * *")
    public void purge() {
        repository.deleteOlderThan(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
    }

    /** from 이후의 1분 이력 (오래된 순). 아직 저장 전인 "지금 이 1분"도 마지막에 붙인다. */
    public List<ProcessHistory> since(Long serverId, Instant from) {
        List<ProcessHistory> rows = new ArrayList<>(
                repository.findByServerIdAndTimeGreaterThanEqualOrderByTimeAsc(serverId, from));
        synchronized (current) {
            Bucket b = current.get(serverId);
            if (b != null && !b.minute.isBefore(from)
                    && rows.stream().noneMatch(r -> r.getTime().equals(b.minute))) {
                rows.addAll(b.rows(serverId));
            }
        }
        return rows;
    }

    /** 화면 표시용 */
    public static Map<String, Object> toMap(ProcessHistory h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", h.getTime().toEpochMilli());
        m.put("name", h.getName());
        m.put("pid", h.getPid());
        m.put("cpu", h.getCpu());
        m.put("mem", h.getMem());
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
