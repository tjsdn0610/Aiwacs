package com.sysone.aiwacs.ai;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 운영 도우미 API (운영 브리핑 / 진단 / 자연어 임계치 설정) */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    /** 전 서버 상황을 한 번에 요약한 운영 브리핑 (요청 본문 없음) */
    @PostMapping("/briefing")
    public Map<String, Object> briefing() {
        return aiService.briefing();
    }

    /** 한 서버의 여러 신호를 모아 원인 사슬로 추적 */
    @PostMapping("/rootcause")
    public Map<String, Object> rootcause(@RequestBody Map<String, Object> body) {
        return aiService.rootcause(parseId(body.get("serverId")));
    }

    /** 처리내역 AI 초안 생성 */
    @PostMapping("/note/draft")
    public Map<String, Object> noteDraft(@RequestBody Map<String, Object> body) {
        return aiService.noteDraft(parseId(body.get("serverId")));
    }

    /** 담당자가 검토·승인한 처리내역 저장 */
    @PostMapping("/note/save")
    public Map<String, Object> noteSave(@RequestBody Map<String, Object> body) {
        return aiService.saveNote(parseId(body.get("serverId")),
                body.get("content") == null ? "" : String.valueOf(body.get("content")));
    }

    /** 저장된 처리내역(지식 베이스) 최근 목록 */
    @GetMapping("/note/history")
    public List<Map<String, Object>> noteHistory() {
        return aiService.noteHistory();
    }

    /** 정책 튜닝 점검 (실측 vs 임계치) */
    @PostMapping("/tuning")
    public Map<String, Object> tuning() {
        return aiService.policyTuning();
    }

    /** 튜닝 추천을 실제 적용 */
    @PostMapping("/tuning/apply")
    public Map<String, Object> tuningApply(@RequestBody Map<String, Object> body) {
        int value;
        try {
            value = (int) Math.round(Double.parseDouble(String.valueOf(body.get("value"))));
        } catch (NumberFormatException e) {
            return Map.of("ok", false, "reply", "적용할 값을 이해하지 못했습니다.");
        }
        return aiService.applyTuning(
                String.valueOf(body.getOrDefault("company", "")),
                String.valueOf(body.getOrDefault("policy", "")),
                body.get("metric") == null ? null : String.valueOf(body.get("metric")),
                body.get("level") == null ? null : String.valueOf(body.get("level")),
                value);
    }

    /** 서술형 보고서 / 교대 인수인계 */
    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody Map<String, Object> body) {
        int hours = 8;
        try {
            if (body.get("hours") != null) {
                hours = Integer.parseInt(String.valueOf(body.get("hours")));
            }
        } catch (NumberFormatException ignored) {
            // 기본 8시간
        }
        return aiService.report(hours);
    }

    /** 자연어 통합 질의 */
    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, Object> body) {
        return aiService.query(body.get("message") == null ? "" : String.valueOf(body.get("message")));
    }

    @PostMapping("/threshold")
    public Map<String, Object> threshold(@RequestBody Map<String, Object> body) {
        Object msg = body.getOrDefault("message", "");
        return aiService.changeThreshold(String.valueOf(msg));
    }

    private static Long parseId(Object raw) {
        try {
            return raw == null ? null : Long.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @PostMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestBody Map<String, Object> body) {
        Object id = body.get("serverId");
        Long serverId = null;
        try {
            serverId = id == null ? null : Long.valueOf(String.valueOf(id));
        } catch (NumberFormatException ignored) {
            // 잘못된 값이면 null → "서버를 선택해 주세요" 안내
        }
        return aiService.diagnose(serverId);
    }
}
