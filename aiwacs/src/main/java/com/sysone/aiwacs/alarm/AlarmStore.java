package com.sysone.aiwacs.alarm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 알람·처리 기록을 DB에 저장하고 다시 읽는다.
 * 저장에 실패해도 알람 엔진(판정·발생·해제)은 멈추지 않도록 예외는 기록만 남긴다.
 */
@Component
public class AlarmStore {

    private static final Logger log = LoggerFactory.getLogger(AlarmStore.class);

    private final AlarmRowRepository alarms;
    private final AlarmProcessRowRepository processes;

    public AlarmStore(AlarmRowRepository alarms, AlarmProcessRowRepository processes) {
        this.alarms = alarms;
        this.processes = processes;
    }

    /** 알람 상태(발생 횟수·해제 등)를 저장 */
    public void save(Alarm a) {
        try {
            alarms.save(AlarmRow.of(a));
        } catch (Exception e) {
            log.warn("알람 저장 실패 (id {}): {}", a.getId(), e.getMessage());
        }
    }

    /** 처리 기록 한 줄 저장 */
    public void saveProcess(long alarmId, Alarm.ProcessRecord r) {
        try {
            processes.save(AlarmProcessRow.of(alarmId, r));
        } catch (Exception e) {
            log.warn("처리 기록 저장 실패 (알람 {}): {}", alarmId, e.getMessage());
        }
    }

    public long maxId() {
        Long m = alarms.maxId();
        return m == null ? 0 : m;
    }

    /** 최근 알람을 처리 기록과 함께 되살린다 (최신순) */
    public List<Alarm> loadRecent(int limit) {
        return withProcesses(alarms.findAllByOrderByFirstAtDesc(PageRequest.of(0, limit)));
    }

    /** 같은 서버·자원에서 before보다 앞서 생긴 알람 (과거 비슷한 이벤트 찾기용, 최신순) */
    public List<Alarm> findPast(Long serverId, String resource, Instant before) {
        return withProcesses(alarms.findByServerIdAndResourceAndFirstAtBeforeOrderByFirstAtDesc(serverId, resource, before));
    }

    private List<Alarm> withProcesses(List<AlarmRow> rows) {
        Map<Long, Alarm> byId = new LinkedHashMap<>();
        for (AlarmRow r : rows) {
            byId.put(r.getId(), r.toAlarm());
        }
        if (!byId.isEmpty()) {
            for (AlarmProcessRow p : processes.findByAlarmIdInOrderByAtAsc(byId.keySet())) {
                byId.get(p.getAlarmId()).restoreProcess(p.toRecord());
            }
        }
        return new ArrayList<>(byId.values());
    }
}
