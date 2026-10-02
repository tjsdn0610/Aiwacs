package com.sysone.aiwacs.action;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 사람이 승인한 조치 한 건 (예: server1의 stress-ng(PID 1234) 정상 종료).
 * 프로토타입 단계라 알람과 같이 메모리에만 둔다(재시작 시 초기화).
 *
 * 흐름: PENDING(승인됨, Agent가 가져가길 기다림) → SENT(Agent에 전달) → DONE / FAILED
 *       30초 안에 Agent가 안 가져가면 EXPIRED (오래된 명령이 나중에 실행되지 않게)
 *       시뮬레이션 모드면 Agent에 보내지 않고 바로 SIMULATED
 */
public class ActionCommand {

    public enum Status { PENDING, SENT, DONE, FAILED, EXPIRED, SIMULATED }

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final long id;
    private final Long serverId;
    private final String server;
    private final String action;   // terminate | renice
    private final long pid;
    private final String name;
    private final long start;      // 프로세스 시작 시각 (Agent가 같은 프로세스인지 재확인)
    private final String by;       // 승인한 사람
    private final String reason;   // 승인 당시 근거 (AI 진단 요약 등, 기록용)
    private final Instant createdAt;
    private Instant sentAt;
    private Instant doneAt;
    private Status status = Status.PENDING;
    private String message;

    public ActionCommand(long id, Long serverId, String server, String action, long pid, String name, long start,
                         String by, String reason, Instant now) {
        this.id = id;
        this.serverId = serverId;
        this.server = server;
        this.action = action;
        this.pid = pid;
        this.name = name;
        this.start = start;
        this.by = by;
        this.reason = reason;
        this.createdAt = now;
    }

    public static String actionKr(String action) {
        return switch (action) {
            case "terminate" -> "정상 종료";
            case "renice" -> "우선순위 낮추기";
            default -> action;
        };
    }

    void sent(Instant now) {
        status = Status.SENT;
        sentAt = now;
    }

    void finish(Status s, String msg, Instant now) {
        status = s;
        message = msg;
        doneAt = now;
    }

    public long getId() { return id; }
    public Long getServerId() { return serverId; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }

    /** Agent에 보내는 명령 (필요한 값만) */
    Map<String, Object> toAgent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("action", action);
        m.put("pid", pid);
        m.put("name", name);
        m.put("start", start);
        m.put("by", by);
        return m;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("serverId", serverId);
        m.put("server", server);
        m.put("action", action);
        m.put("actionKr", actionKr(action));
        m.put("pid", pid);
        m.put("name", name);
        m.put("by", by);
        m.put("reason", reason);
        m.put("status", status.name());
        m.put("message", message);
        m.put("createdAt", FMT.format(createdAt));
        m.put("doneAt", doneAt == null ? null : FMT.format(doneAt));
        return m;
    }
}
