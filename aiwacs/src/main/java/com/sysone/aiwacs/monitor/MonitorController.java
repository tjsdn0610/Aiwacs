package com.sysone.aiwacs.monitor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sysone.aiwacs.monitor.MetricsService.ProcInfo;
import com.sysone.aiwacs.policy.PolicyService;

/** 메인 대시보드용 실시간 모니터링 API */
@RestController
@RequestMapping("/api")
public class MonitorController {

    private final MetricsService metrics;
    private final PolicyService policyService;

    public MonitorController(MetricsService metrics, PolicyService policyService) {
        this.metrics = metrics;
        this.policyService = policyService;
    }

    /** CPU/메모리/디스크 현재값 + 판정 */
    @GetMapping("/status")
    public Map<String, Map<String, Object>> status() {
        return policyService.currentStatus();
    }

    /** CPU 사용률 상위 10개 프로세스 */
    @GetMapping("/procs")
    public List<ProcInfo> procs() {
        return metrics.processes().stream()
                .sorted(Comparator.comparingDouble(ProcInfo::cpu).reversed())
                .limit(10)
                .toList();
    }

    @GetMapping("/disk")
    public List<Map<String, Object>> disk() {
        return metrics.partitions();
    }

    @GetMapping("/traffic")
    public Map<String, Double> traffic() {
        return metrics.traffic();
    }
}
