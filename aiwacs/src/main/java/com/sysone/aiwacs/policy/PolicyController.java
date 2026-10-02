package com.sysone.aiwacs.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 알림정책 CRUD API */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyService service;

    public PolicyController(PolicyService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("policies", service.findAllGroupedByCompany()); // 화면에는 고객사별로 모아서
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody PolicyRequest req) {
        String error = service.validate(req);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", error));
        }
        Policy saved = service.add(req);
        return ResponseEntity.ok(Map.of("ok", true, "id", saved.getId()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody PolicyRequest req) {
        String error = service.validate(req);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", error));
        }
        if (!service.update(id, req)) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not found"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 정책 팝업 '장비' 탭 저장 — 이 정책을 적용할 서버 목록.
     * body: {"serverIds": [1, 2]}  (목록에 없는 서버 중 이 정책을 쓰던 서버는 기본 정책으로)
     */
    @PutMapping("/{id}/servers")
    public ResponseEntity<Map<String, Object>> assignServers(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        if (body.get("serverIds") instanceof List<?> list) {
            for (Object o : list) {
                try {
                    ids.add(Long.valueOf(String.valueOf(o)));
                } catch (NumberFormatException ignored) {
                    // 잘못된 id는 건너뜀
                }
            }
        }
        PolicyService.AssignResult r = service.assignServers(id, ids);
        if (!r.ok()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not found"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "assigned", r.assigned(), "released", r.released(),
                "skipped", r.skipped()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        return Map.of("ok", true, "deleted", service.delete(id));
    }
}
