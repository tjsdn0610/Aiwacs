package com.sysone.aiwacs.alarm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 알람 조회(발생/이력/처리) + 처리 기록 API */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarms;

    public AlarmController(AlarmService alarms) {
        this.alarms = alarms;
    }

    /** 현재 발생 중 알람 (종/배지용) */
    @GetMapping("/active")
    public List<Map<String, Object>> active() {
        return alarms.activeAlarms();
    }

    /** 알림 내역 (발생·해제·처리 전체) */
    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return alarms.history();
    }

    /** 처리 내역 */
    @GetMapping("/handled")
    public List<Map<String, Object>> handled() {
        return alarms.handled();
    }

    /**
     * 선택 알람에 처리 기록 추가 — body: {"ids":[1,2], "status":"MAINTENANCE", "note":"...", "by":"..."}
     * status: IGNORE(무시) / MAINTENANCE(점검 중) / HOLD(보류) / COMPLETE(완료). 없으면 COMPLETE.
     */
    @PostMapping("/handle")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> body) {
        Alarm.ProcessStatus status;
        try {
            status = body.get("status") == null ? Alarm.ProcessStatus.COMPLETE
                    : Alarm.ProcessStatus.valueOf(String.valueOf(body.get("status")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "알 수 없는 처리 상태입니다."));
        }
        int n = alarms.handle(parseIds(body.get("ids")), status,
                body.get("by") == null ? null : String.valueOf(body.get("by")),
                body.get("note") == null ? "" : String.valueOf(body.get("note")));
        return ResponseEntity.ok(Map.of("ok", true, "handled", n));
    }

    /** [1, "2", ...] → id 목록 (잘못된 값은 건너뜀) */
    public static List<Long> parseIds(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                try {
                    ids.add(Long.valueOf(String.valueOf(o)));
                } catch (NumberFormatException ignored) {
                    // 잘못된 id는 건너뜀
                }
            }
        }
        return ids;
    }
}
