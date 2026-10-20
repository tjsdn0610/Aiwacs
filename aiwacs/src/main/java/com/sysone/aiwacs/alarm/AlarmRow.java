package com.sysone.aiwacs.alarm;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 알람 한 건의 DB 저장본 (실제 AiWACS의 tbl_alarm_event에 해당).
 * 알람 엔진은 메모리의 Alarm으로 판정·갱신하고, 바뀔 때마다 이 줄을 덮어쓴다.
 * 앱을 다시 켜면 최근 알람을 이 테이블에서 읽어 메모리로 되살린다.
 */
@Entity
@Table(name = "alarm_event", indexes = { @Index(columnList = "serverId, resource, firstAt"), @Index(columnList = "firstAt") })
public class AlarmRow {

    /** Alarm의 id 그대로 (알람 엔진이 번호를 매긴다) */
    @Id
    private Long id;

    @Column(nullable = false)
    private Long serverId;
    private String server;
    private String company;
    @Column(nullable = false)
    private String resource;
    @Column(nullable = false)
    private String metric;
    @Column(nullable = false)
    private String level;
    private int threshold;
    private double firstValue;
    private double lastValue;
    @Column(nullable = false)
    private Instant firstAt;
    private Instant lastAt;
    private Instant lastCountAt;
    private Instant resolvedAt;
    private int count;
    @Column(nullable = false)
    private String status;

    protected AlarmRow() {
    }

    static AlarmRow of(Alarm a) {
        AlarmRow r = new AlarmRow();
        r.id = a.getId();
        r.copyFrom(a);
        return r;
    }

    void copyFrom(Alarm a) {
        serverId = a.getServerId();
        server = a.getServer();
        company = a.getCompany();
        resource = a.getResource();
        metric = a.getMetric();
        level = a.getLevel();
        threshold = a.getThreshold();
        firstValue = a.getFirstValue();
        lastValue = a.getValue();
        firstAt = a.getFirstAt();
        lastAt = a.getLastAt();
        lastCountAt = a.getLastCountAt();
        resolvedAt = a.getResolvedAt();
        count = a.getCount();
        status = a.getStatus().name();
    }

    Alarm toAlarm() {
        return Alarm.restore(id, serverId, server, company, resource, metric, level, threshold, firstValue, lastValue,
                firstAt, lastAt, lastCountAt, resolvedAt, count, Alarm.Status.valueOf(status));
    }

    public Long getId() { return id; }
}
