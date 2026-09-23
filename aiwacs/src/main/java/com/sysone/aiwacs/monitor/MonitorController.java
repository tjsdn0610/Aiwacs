package com.sysone.aiwacs.monitor;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sysone.aiwacs.server.AgentReport;
import com.sysone.aiwacs.server.ServerService;

/**
 * 메인 대시보드용 모니터링 API.
 * 선택한 서버(serverId)의 Agent가 보낸 최신 지표를 돌려준다.
 */
@RestController
@RequestMapping("/api")
public class MonitorController {

    private final ServerService servers;

    public MonitorController(ServerService servers) {
        this.servers = servers;
    }

    /** CPU/메모리/디스크 현재값 + 판정 (그 서버에 적용된 정책 기준) */
    @GetMapping("/status")
    public ResponseEntity<Object> status(@RequestParam Long serverId) {
        return servers.judge(serverId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of("ok", false, "error", "no data")));
    }

    /** 프로세스 목록 (Agent가 보낸 CPU·메모리 상위 프로세스, CPU 높은 순) */
    @GetMapping("/procs")
    public ResponseEntity<Object> procs(@RequestParam Long serverId) {
        return fromLatest(serverId, r -> r.procs().stream()
                .sorted(Comparator.comparingDouble(AgentReport.Proc::cpu).reversed())
                .toList());
    }

    /** 파티션별 디스크 사용률 */
    @GetMapping("/disk")
    public ResponseEntity<Object> disk(@RequestParam Long serverId) {
        return fromLatest(serverId, AgentReport::partitions);
    }

    /** 송수신 속도 (KB/s) */
    @GetMapping("/traffic")
    public ResponseEntity<Object> traffic(@RequestParam Long serverId) {
        return fromLatest(serverId, AgentReport::traffic);
    }

    /** 서버의 최신 지표가 있으면 변환해서 돌려주고, 없으면 404 */
    private ResponseEntity<Object> fromLatest(Long serverId, Function<AgentReport, Object> fn) {
        return servers.latest(serverId)
                .<ResponseEntity<Object>>map(s -> ResponseEntity.ok(fn.apply(s.report())))
                .orElse(ResponseEntity.status(404).body(Map.of("ok", false, "error", "no data")));
    }
}
