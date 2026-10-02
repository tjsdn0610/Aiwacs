package com.sysone.aiwacs.action;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 조치 실행 API — 화면의 확인 창에서 사람이 [실행]을 눌렀을 때만 호출된다 */
@RestController
@RequestMapping("/api/actions")
public class ActionController {

    private final ActionService actions;

    public ActionController(ActionService actions) {
        this.actions = actions;
    }

    /** body: {"serverId":1, "action":"terminate|renice", "pid":1234, "name":"stress-ng", "start":1790..., "by":"...", "reason":"..."} */
    @PostMapping
    public ResponseEntity<Map<String, Object>> request(@RequestBody Map<String, Object> body) {
        Long serverId;
        long pid, start;
        try {
            serverId = Long.valueOf(String.valueOf(body.get("serverId")));
            pid = Long.parseLong(String.valueOf(body.get("pid")));
            start = Long.parseLong(String.valueOf(body.get("start")));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "요청 값이 올바르지 않습니다."));
        }
        ActionService.Result r = actions.request(serverId, String.valueOf(body.get("action")), pid,
                String.valueOf(body.get("name")), start,
                body.get("by") == null ? null : String.valueOf(body.get("by")),
                body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        return r.ok()
                ? ResponseEntity.ok(Map.of("ok", true, "command", r.command().toMap()))
                : ResponseEntity.badRequest().body(Map.of("ok", false, "error", r.error()));
    }

    /** 조치 하나의 진행 상태 (화면이 결과가 나올 때까지 확인) */
    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable long id) {
        return actions.find(id).<ResponseEntity<Object>>map(c -> ResponseEntity.ok(c.toMap()))
                .orElse(ResponseEntity.status(404).body(Map.of("ok", false, "error", "not found")));
    }

    /** 조치 이력 (최신순) */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long serverId) {
        return actions.list(serverId);
    }
}
