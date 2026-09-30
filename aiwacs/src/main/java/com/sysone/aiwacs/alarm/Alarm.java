package com.sysone.aiwacs.alarm;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 발생한 알람 한 건.
 * 프로토타입 단계라 메모리에만 둔다(재시작 시 초기화).
 * AiWACS처럼 CPU 한 사건이 CPU/CPU Core/CPU User 여러 지표로 나뉘어 뜨는 것을 재현하기 위해
 * resource(cpu/memory/disk)는 같아도 metric(표시 지표명)은 여러 개가 될 수 있다.
 */
public class Alarm {

    public enum Status { ACTIVE, RESOLVED, HANDLED }

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final long id;
    private final Long serverId;
    private final String server;
    private final String company;
    private final String resource;  // 그룹핑 기준: cpu / memory / disk
    private final String metric;    // 표시 지표: CPU / CPU Core / CPU User / 메모리 / 디스크
    private String level;           // 주의 / 위험
    private double value;
    private final Instant firstAt;
    private Instant lastAt;
    private int count;
    private Status status;
    private String handledBy;
    private String handleNote;
    private Instant handledAt;

    public Alarm(long id, Long serverId, String server, String company,
                 String resource, String metric, String level, double value, Instant now) {
        this.id = id;
        this.serverId = serverId;
        this.server = server;
        this.company = company;
        this.resource = resource;
        this.metric = metric;
        this.level = level;
        this.value = value;
        this.firstAt = now;
        this.lastAt = now;
        this.count = 1;
        this.status = Status.ACTIVE;
    }

    /** 같은 조건이 다시 감지될 때: 값·시각 갱신 + 발생횟수 증가 (=같은 알람이 계속 온다) */
    public void refresh(String level, double value, Instant now) {
        this.level = level;
        this.value = value;
        this.lastAt = now;
        this.count++;
    }

    public void resolve() {
        if (status == Status.ACTIVE) {
            status = Status.RESOLVED;
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
    public String getResource() { return resource; }
    public Status getStatus() { return status; }

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
        m.put("level", level);
        m.put("value", Math.round(value * 10) / 10.0);
        m.put("count", count);
        m.put("firstAt", FMT.format(firstAt));
        m.put("lastAt", FMT.format(lastAt));
        m.put("durationSec", durationSec(firstAt, lastAt));
        m.put("status", status.name());
        m.put("handledBy", handledBy);
        m.put("handleNote", handleNote);
        m.put("handledAt", handledAt == null ? null : FMT.format(handledAt));
        return m;
    }
}
