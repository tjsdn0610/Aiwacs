package com.sysone.aiwacs.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sysone.aiwacs.monitor.MetricsService;

/**
 * 알림 정책 관리 + 임계치 판정.
 * 설계 원칙: "판정은 코드" — 정상/주의/위험은 AI가 아니라 여기서 정책 값으로 결정한다.
 */
@Service
public class PolicyService {

    private final PolicyRepository repository;
    private final MetricsService metrics;

    public PolicyService(PolicyRepository repository, MetricsService metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /** 처음 실행 시(정책이 하나도 없을 때) 샘플 정책 생성 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaults() {
        if (repository.count() > 0) {
            return;
        }
        repository.saveAll(List.of(
                new Policy("테라넷", "DB서버 정책", new Threshold(70, 90), new Threshold(80, 90), new Threshold(80, 95)),
                new Policy("테라넷", "웹서버 정책", new Threshold(85, 95), new Threshold(90, 95), new Threshold(85, 95)),
                new Policy("ABC", "ERP서버 정책", new Threshold(75, 90), new Threshold(80, 92), new Threshold(80, 95))));
    }

    public List<Policy> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    @Transactional
    public Policy add(PolicyRequest req) {
        Policy p = new Policy(
                orDefault(req.company(), "미지정"),
                orDefault(req.name(), "새 정책"),
                req.cpu() != null ? req.cpu() : new Threshold(70, 90),
                req.memory() != null ? req.memory() : new Threshold(80, 90),
                req.disk() != null ? req.disk() : new Threshold(80, 95));
        return repository.save(p);
    }

    @Transactional
    public boolean update(Long id, PolicyRequest req) {
        Optional<Policy> found = repository.findById(id);
        if (found.isEmpty()) {
            return false;
        }
        Policy p = found.get();
        if (req.company() != null) p.setCompany(req.company());
        if (req.name() != null) p.setName(req.name());
        if (req.cpu() != null) p.setCpu(req.cpu());
        if (req.memory() != null) p.setMemory(req.memory());
        if (req.disk() != null) p.setDisk(req.disk());
        return true;
    }

    @Transactional
    public int delete(Long id) {
        if (!repository.existsById(id)) {
            return 0;
        }
        repository.deleteById(id);
        return 1;
    }

    public record ChangeResult(boolean ok, String message) {}

    private static final Map<String, String> METRIC_KR = Map.of("cpu", "CPU", "memory", "메모리", "disk", "디스크");
    private static final Map<String, String> LEVEL_KR = Map.of("warn", "주의", "danger", "위험");

    /**
     * 임계치 한 건 변경. AI가 번역한 명령을 코드가 검증한 뒤 실제로 저장한다.
     * 정책은 기업명·정책명이 포함되는 첫 번째 정책으로 찾는다.
     */
    @Transactional
    public ChangeResult changeThreshold(String company, String policyName, String metric, String level, int value) {
        Policy target = findAll().stream()
                .filter(p -> p.getCompany().contains(company) && p.getName().contains(policyName))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return new ChangeResult(false, "'" + company + " " + policyName + "' 정책을 찾을 수 없습니다.");
        }
        Threshold th = metric == null ? null : target.threshold(metric);
        if (th == null || !LEVEL_KR.containsKey(level)) {
            return new ChangeResult(false, "해당 항목을 찾을 수 없습니다.");
        }
        if (value < 0 || value > 100) {
            return new ChangeResult(false, "임계치는 0~100% 범위여야 합니다. (요청값: " + value + "%)");
        }

        int old = "warn".equals(level) ? th.getWarn() : th.getDanger();
        // Embeddable은 새 객체로 교체해야 변경이 확실히 반영된다
        Threshold updated = "warn".equals(level)
                ? new Threshold(value, th.getDanger())
                : new Threshold(th.getWarn(), value);
        switch (metric) {
            case "cpu" -> target.setCpu(updated);
            case "memory" -> target.setMemory(updated);
            default -> target.setDisk(updated);
        }
        repository.save(target);
        return new ChangeResult(true, "[" + target.getCompany() + "] " + target.getName() + " · "
                + METRIC_KR.get(metric) + " " + LEVEL_KR.get(level) + " " + old + "% → " + value + "%");
    }

    /** 메인 대시보드는 "대표 정책"(첫 번째)으로 판정 (현재 실제 서버 1대 기준) */
    public Optional<Policy> activePolicy() {
        return repository.findFirstByOrderByIdAsc();
    }

    public static String judge(double value, Threshold th) {
        if (value >= th.getDanger()) return "위험";
        if (value >= th.getWarn()) return "주의";
        return "정상";
    }

    /** 현재 CPU/메모리/디스크 값 + 대표 정책 기준 판정 결과 */
    public Map<String, Map<String, Object>> currentStatus() {
        Policy pol = activePolicy().orElse(null);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        metrics.coreMetrics().forEach((key, value) -> {
            String status = pol != null ? judge(value, pol.threshold(key)) : "정상";
            result.put(key, Map.of("value", MetricsService.round1(value), "status", status));
        });
        return result;
    }

    private static String orDefault(String v, String def) {
        return v != null ? v : def;
    }
}
