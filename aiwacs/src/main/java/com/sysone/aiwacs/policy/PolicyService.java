package com.sysone.aiwacs.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sysone.aiwacs.server.ServerRepository;

/**
 * 알림 정책 관리 + 임계치 판정.
 * 설계 원칙: "판정은 코드" — 정상/주의/위험은 AI가 아니라 여기서 정책 값으로 결정한다.
 */
@Service
public class PolicyService {

    private final PolicyRepository repository;
    private final ServerRepository serverRepository;

    public PolicyService(PolicyRepository repository, ServerRepository serverRepository) {
        this.repository = repository;
        this.serverRepository = serverRepository;
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
        serverRepository.clearPolicy(id); // 이 정책을 쓰던 서버는 기본 정책으로
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

    /** 기본 정책 = 첫 번째 정책 (정책을 지정하지 않은 서버에 적용) */
    public Optional<Policy> defaultPolicy() {
        return repository.findFirstByOrderByIdAsc();
    }

    public Optional<Policy> findById(Long id) {
        return repository.findById(id);
    }

    /**
     * 서버에 적용할 정책.
     * 1) 지정한 정책이 있으면 그 정책
     * 2) 없으면 그 서버 고객사의 첫 번째 정책
     * 3) 고객사가 없거나 고객사 정책이 없으면 전체 첫 번째 정책
     */
    public Optional<Policy> policyFor(Long policyId, String company) {
        if (policyId != null) {
            Optional<Policy> assigned = repository.findById(policyId);
            if (assigned.isPresent()) {
                return assigned;
            }
        }
        if (company != null) {
            Optional<Policy> companyDefault = findAll().stream()
                    .filter(p -> company.equals(p.getCompany()))
                    .findFirst();
            if (companyDefault.isPresent()) {
                return companyDefault;
            }
        }
        return defaultPolicy();
    }

    /** 고객사 목록 (정책에 등록된 고객사, 등록 순) */
    public List<String> companies() {
        return findAll().stream().map(Policy::getCompany).distinct().toList();
    }

    public static String judge(double value, Threshold th) {
        if (value >= th.getDanger()) return "위험";
        if (value >= th.getWarn()) return "주의";
        return "정상";
    }

    private static final List<String> CORE_METRICS = List.of("cpu", "memory", "disk");

    /** 서버가 보낸 CPU/메모리/디스크 값을 주어진 정책으로 판정 (정책이 없으면 모두 정상) */
    public Map<String, Map<String, Object>> judgeAll(Map<String, Double> core, Policy pol) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String key : CORE_METRICS) {
            double value = core.getOrDefault(key, 0.0);
            String status = pol != null ? judge(value, pol.threshold(key)) : "정상";
            result.put(key, Map.of("value", Math.round(value * 10) / 10.0, "status", status));
        }
        return result;
    }

    /** 판정 결과 중 가장 심각한 상태 (서버 한 대의 대표 상태) */
    public static String worst(Map<String, Map<String, Object>> judged) {
        List<Object> statuses = judged.values().stream().map(m -> m.get("status")).toList();
        if (statuses.contains("위험")) return "위험";
        if (statuses.contains("주의")) return "주의";
        return "정상";
    }

    private static String orDefault(String v, String def) {
        return v != null ? v : def;
    }
}
