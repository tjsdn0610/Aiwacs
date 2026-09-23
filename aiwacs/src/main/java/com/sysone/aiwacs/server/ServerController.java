package com.sysone.aiwacs.server;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.sysone.aiwacs.policy.PolicyService;

import jakarta.servlet.http.HttpServletRequest;

/** Agent 지표 수신 + 모니터링 서버 조회 API */
@RestController
public class ServerController {

    private final ServerService service;
    private final PolicyService policyService;
    private final String agentToken;

    public ServerController(ServerService service, PolicyService policyService,
                            @Value("${agent.token:}") String agentToken) {
        this.service = service;
        this.policyService = policyService;
        this.agentToken = agentToken;
    }

    /** Agent → AiWACS 지표 전송. agent.token이 설정돼 있으면 같은 토큰을 가진 Agent만 허용. */
    @PostMapping("/api/agent/metrics")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestBody AgentReport report,
            HttpServletRequest request) {
        if (!agentToken.isBlank() && !tokenMatches(token)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "invalid agent token"));
        }
        if (report.serverName() == null || report.serverName().isBlank() || report.serverName().length() > 50
                || report.core() == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "invalid report"));
        }
        MonitoredServer server = service.receive(report, request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("ok", true, "serverId", server.getId()));
    }

    /** 모니터링 서버 목록 (온라인 여부 + 서버별 대표 상태 포함) */
    @GetMapping("/api/servers")
    public List<Map<String, Object>> servers() {
        return service.findAll().stream().map(s -> {
            Map<String, Object> m = service.summary(s);
            // 오프라인이면 판정하지 않음 (마지막 값으로 정상/위험을 표시하면 오해 소지)
            m.put("level", service.isOnline(s.getId())
                    ? service.judge(s.getId()).map(PolicyService::worst).orElse(null)
                    : null);
            return m;
        }).toList();
    }

    /**
     * 서버 정보 수정 (장비 목록 화면).
     * {"displayName": "테라넷-DB01", "company": "테라넷", "policyId": 1}  — 빈 값/null이면 미지정(기본값)
     */
    @PutMapping("/api/servers/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long policyId = null;
        Object raw = body.get("policyId");
        if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                policyId = Long.valueOf(String.valueOf(raw));
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "잘못된 정책 값입니다."));
            }
        }
        ServerService.UpdateResult r = service.update(id,
                body.get("displayName") == null ? null : String.valueOf(body.get("displayName")),
                body.get("company") == null ? null : String.valueOf(body.get("company")),
                policyId);
        return r.ok()
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(r.status()).body(Map.of("ok", false, "error", r.error()));
    }

    /** 고객사 목록 (정책에 등록된 고객사) */
    @GetMapping("/api/companies")
    public List<String> companies() {
        return policyService.companies();
    }

    /** 서버의 최신 지표 원본 (Agent 연결 확인용) */
    @GetMapping("/api/servers/{id}/latest")
    public ResponseEntity<Object> latest(@PathVariable Long id) {
        return service.latest(id)
                .<ResponseEntity<Object>>map(s -> ResponseEntity.ok(s))
                .orElse(ResponseEntity.status(404).body(Map.of("ok", false, "error", "no data")));
    }

    /** 토큰 비교 (응답 시간 차이로 토큰을 추측할 수 없게 일정 시간 비교) */
    private boolean tokenMatches(String token) {
        return token != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), agentToken.getBytes(StandardCharsets.UTF_8));
    }
}
