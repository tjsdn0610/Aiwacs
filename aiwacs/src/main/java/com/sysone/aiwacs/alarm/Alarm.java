package com.sysone.aiwacs.alarm;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 발생한 알람 한 건.
 * 프로토타입 단계라 메모리에만 둔다(재시작 시 초기화).
 *
 * 실제 AiWACS 부하 실측(2026-10-01)과 같은 방식:
 * - CPU 한 사건이 CPU / CPU Core / CPU User 여러 지표로 나뉘어 뜬다 (resource는 같고 metric이 여러 개).
 * - 레벨마다 알람이 따로 생긴다. CPU가 장애까지 오르면 주의·경고·위험·장애 알람이 각각 열려 있고,
 *   값이 그 레벨 기준 아래로 내려가면 그 레벨 알람만 해제된다.
 * - 조건이 계속되는 동안 발생 횟수가 일정 간격(1분)마다 1씩 쌓인다.
 */
public class Alarm {

    public enum Status { ACTIVE, RESOLVED, HANDLED }

    /** 같은 알람이 계속될 때 발생 횟수를 1 올리는 간격 */
    static final Duration COUNT_INTERVAL = Duration.ofMinutes(1);

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final long id;
    private final Long serverId;
    private final String server;
    private final String company;
    private final String resource;  // 묶음 기준: cpu / memory / disk
    private final String metric;    // 표시 지표: CPU / CPU Core / CPU User / 메모리 / 디스크
    private final String level;     // 주의 / 경고 / 위험 / 장애 (알람마다 고정)
    private final int threshold;    // 이 레벨의 기준값(%) — AiWACS의 '알람수치'
    private final double firstValue;
    private double value;
    private final Instant firstAt;
    private Instant lastAt;
    private Instant lastCountAt;
    private Instant resolvedAt;
    private int count;
    private Status status;
    private String handledBy;
    private String handleNote;
    private Instant handledAt;

    public Alarm(long id, Long serverId, String server, String company, String resource, String metric,
                 String level, int threshold, double value, Instant now) {
        this.id = id;
        this.serverId = serverId;
        this.server = server;
        this.company = company;
        this.resource = resource;
        this.metric = metric;
        this.level = level;
        this.threshold = threshold;
        this.firstValue = value;
        this.value = value;
        this.firstAt = now;
        this.lastAt = now;
        this.lastCountAt = now;
        this.count = 1;
        this.status = Status.ACTIVE;
    }

    /** 같은 조건이 다시 감지될 때: 현재값 갱신, 1분이 지났으면 발생 횟수 +1 (=같은 알람이 계속 온다) */
    public void refresh(double value, Instant now) {
        this.value = value;
        if (!now.isBefore(lastCountAt.plus(COUNT_INTERVAL))) {
            this.count++;
            this.lastCountAt = now;
            this.lastAt = now;
        }
    }

    public void resolve(Instant now) {
        if (status == Status.ACTIVE) {
            status = Status.RESOLVED;
            resolvedAt = now;
        }
    }

    public void handle(String by, String note, Instant now) {
        this.status = Status.HANDLED;
        this.handledBy = by;
        this.handleNote = note;
        this.handledAt = now;
    }

    public long getId() { return id; }
    public Long getServerId() { return serverId; }
    public String getServer() { return server; }
    public String getResource() { return resource; }
    public String getMetric() { return metric; }
    public String getLevel() { return level; }
    public int getCount() { return count; }
    public Instant getFirstAt() { return firstAt; }
    public Instant getLastAt() { return lastAt; }
    public Status getStatus() { return status; }

    /** "CPU User >= 30%" 처럼 AiWACS 알람 설명 형식 */
    public String title() {
        return metric + " >= " + threshold + "%";
    }

    private static long durationSec(Instant a, Instant b) {
        return Math.max(0, b.getEpochSecond() - a.getEpochSecond());
    }

    /** 화면·AI 전달용 JSON */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("server", server);
        m.put("company", company);
        m.put("resource", resource);
        m.put("metric", metric);
        m.put("title", title());
        m.put("level", level);
        m.put("threshold", threshold);
        m.put("firstValue", Math.round(firstValue * 10) / 10.0);
        m.put("value", Math.round(value * 10) / 10.0);
        m.put("count", count);
        m.put("firstAt", FMT.format(firstAt));
        m.put("lastAt", FMT.format(lastAt));
        m.put("resolvedAt", resolvedAt == null ? null : FMT.format(resolvedAt));
        // 지속 시간: 해제됐으면 발생~해제, 아니면 발생~지금
        m.put("durationSec", durationSec(firstAt, resolvedAt != null ? resolvedAt : Instant.now()));
        m.put("status", status.name());
        m.put("handledBy", handledBy);
        m.put("handleNote", handleNote);
        m.put("handledAt", handledAt == null ? null : FMT.format(handledAt));
        return m;
    }
}
