package com.sysone.aiwacs.alarm;

import java.time.Instant;
import java.util.HashSet;
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
 * 몇 초마다 각 서버의 판정(코드가 내린 정상/주의/위험)을 확인해 알람을 생성·갱신·해제한다.
 * AiWACS처럼 CPU가 임계를 넘으면 CPU / CPU Core / CPU User 세 지표로 나뉘어 알람이 뜬다
 * (한 사건이 여러 알람으로 → AI 알림 그룹핑으로 다시 묶는 시연을 위해).
 */
@Service
public class AlarmService {

    /** 자원별 표시 지표 (한 사건이 여러 알람으로 나뉘는 지점) */
    private static final Map<String, String[]> FAMILY = Map.of(
            "cpu", new String[] {"CPU", "CPU Core", "CPU User"},
            "memory", new String[] {"메모리"},
            "disk", new String[] {"디스크"});

    private final ServerService servers;
    private final AtomicLong seq = new AtomicLong();
    /** 현재 발생 중(ACTIVE) 알람: key = serverId|metric */
    private final Map<String, Alarm> active = new ConcurrentHashMap<>();
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
        Set<String> stillActive = new HashSet<>();

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
                if (m == null) {
                    continue;
                }
                String status = String.valueOf(m.get("status"));
                double value = toDouble(m.get("value"));
                if ("정상".equals(status)) {
                    continue;
                }
                for (String metric : FAMILY.get(resource)) {
                    String key = id + "|" + metric;
                    stillActive.add(key);
                    Alarm a = active.get(key);
                    if (a == null) {
                        a = new Alarm(seq.incrementAndGet(), id, s.label(), s.getCompany(),
                                resource, metric, status, value, now);
                        active.put(key, a);
                        all.addFirst(a);
                        while (all.size() > MAX) {
                            all.removeLast();
                        }
                    } else {
                        a.refresh(status, value, now);
                    }
                }
            }
        }
        // 더 이상 조건에 맞지 않는(정상 복귀·오프라인) 알람은 해제
        active.entrySet().removeIf(e -> {
            if (!stillActive.contains(e.getKey())) {
                e.getValue().resolve();
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

    /** 처리 내역: 처리 완료된 것만 */
    public List<Map<String, Object>> handled() {
        return all.stream().filter(a -> a.getStatus() == Alarm.Status.HANDLED).map(Alarm::toMap).toList();
    }

    /** 선택한 알람들을 처리 완료로 기록 */
    public int handle(List<Long> ids, String by, String note) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Set<Long> set = new HashSet<>(ids);
        Instant now = Instant.now();
        int n = 0;
        for (Alarm a : all) {
            if (set.contains(a.getId()) && a.getStatus() != Alarm.Status.HANDLED) {
                a.handle(by == null || by.isBlank() ? "aiwacs" : by, note, now);
                active.remove(a.getServerId() + "|" + a.getResource()); // 안전용
                n++;
            }
        }
        // 처리된 알람은 active에서 제거 (key가 metric 기준이라 별도 정리)
        active.values().removeIf(a -> a.getStatus() == Alarm.Status.HANDLED);
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
