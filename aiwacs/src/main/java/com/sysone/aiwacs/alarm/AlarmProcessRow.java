package com.sysone.aiwacs.alarm;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 알람 처리 기록 한 줄의 DB 저장본 (실제 AiWACS의 tbl_alarm_process_detail에 해당).
 * 한 알람에 여러 줄이 쌓인다 (예: 조치 '점검 중' → 사람의 '완료').
 */
@Entity
@Table(name = "alarm_process", indexes = @Index(columnList = "alarmId"))
public class AlarmProcessRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long alarmId;
    @Column(nullable = false)
    private String status;
    @Column(columnDefinition = "text")
    private String note;
    private String handledBy;
    @Column(nullable = false)
    private Instant at;
    /** true = 승인한 조치의 결과가 자동으로 남은 기록 */
    private boolean action;

    protected AlarmProcessRow() {
    }

    static AlarmProcessRow of(long alarmId, Alarm.ProcessRecord r) {
        AlarmProcessRow p = new AlarmProcessRow();
        p.alarmId = alarmId;
        p.status = r.status().name();
        p.note = r.note();
        p.handledBy = r.by();
        p.at = r.at();
        p.action = r.action();
        return p;
    }

    Alarm.ProcessRecord toRecord() {
        return new Alarm.ProcessRecord(Alarm.ProcessStatus.valueOf(status), note, handledBy, at, action);
    }

    public Long getAlarmId() { return alarmId; }
}
