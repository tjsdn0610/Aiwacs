package com.sysone.aiwacs.ai;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sysone.aiwacs.alarm.AlarmController;

/** AI 운영 도우미 API (진단·조치 제안 / 처리 기록 초안 / 자연어 임계치 설정 / 알림 묶기) */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;
    private final DiagnosisService diagnosis;

    public AiController(AiService aiService, DiagnosisService diagnosis) {
        this.aiService = aiService;
        this.diagnosis = diagnosis;
    }

    /** 처리 기록 초안 — body: {"ids":[1,2], "status":"COMPLETE"} → {"draft": "..."} (저장은 사람이 /api/alarms/handle로) */
    @PostMapping("/handle-draft")
    public Map<String, Object> handleDraft(@RequestBody Map<String, Object> body) {
        return diagnosis.handleDraft(AlarmController.parseIds(body.get("ids")),
                String.valueOf(body.getOrDefault("status", "COMPLETE")));
    }

    @PostMapping("/threshold")
    public Map<String, Object> threshold(@RequestBody Map<String, Object> body) {
        Object msg = body.getOrDefault("message", "");
        return aiService.changeThreshold(String.valueOf(msg));
    }

    /** 종(알림) 목록 — AI 호출 없이 현재 발생 알림만 (?demo=true면 부하 시나리오) */
    @GetMapping("/alarms")
    public List<Map<String, Object>> alarms(@RequestParam(name = "demo", defaultValue = "false") boolean demo) {
        return aiService.listAlarms(demo);
    }

    /** AI 알림 그룹핑 (여러 지표 알림을 한 사건으로 묶기) — body: {"demo": true|false} */
    @PostMapping("/alarm-group")
    public Map<String, Object> alarmGroup(@RequestBody(required = false) Map<String, Object> body) {
        boolean demo = body != null && Boolean.parseBoolean(String.valueOf(body.get("demo")));
        return aiService.groupAlarms(demo);
    }

    /** 상태 진단 — body: {"serverId": 1, "minutes": 30} (minutes 0 = 지금 이 순간만) */
    @PostMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestBody Map<String, Object> body) {
        Object id = body.get("serverId");
        Long serverId = null;
        int minutes = 30;
        try {
            serverId = id == null ? null : Long.valueOf(String.valueOf(id));
            if (body.get("minutes") != null) {
                minutes = Integer.parseInt(String.valueOf(body.get("minutes")));
            }
        } catch (NumberFormatException ignored) {
            // 잘못된 값이면 서버는 null → "서버를 선택해 주세요" 안내, 구간은 기본 30분
        }
        return diagnosis.diagnose(serverId, minutes);
    }
}
