package com.sysone.aiwacs.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sysone.aiwacs.server.MonitoredServer;
import com.sysone.aiwacs.server.ServerRepository;

/**
 * 알림 정책 관리 + 임계치 판정.
 * 설계 원칙: "판정은 코드" — 정상/주의/경고/위험/장애는 AI가 아니라 여기서 정책 값으로 결정한다.
 */
@Service
public class PolicyService {

    private final PolicyRepository repository;
    private final ServerRepository serverRepository;

    public PolicyService(PolicyRepository repository, ServerRepository serverRepository) {
        this.repository = repository;
        this.serverRepository = serverRepository;
    }

    /** 새 정책 기본값 (주의/경고/위험/장애) */
    private static Threshold defaultCpu() { return new Threshold(70, 80, 90, 95); }
    private static Threshold defaultMemory() { return new Threshold(80, 85, 90, 95); }
    private static Threshold defaultDisk() { return new Threshold(80, 90, 95, 98); }

    /**
     * 처음 실행 시(정책이 하나도 없을 때) 샘플 정책 생성.
     * 이미 정책이 있으면, 2단계(주의/위험) 시절 저장된 정책에 비어 있는 경고·장애 값을 채워 넣는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaults() {
        if (repository.count() > 0) {
            for (Policy p : repository.findAll()) {
                p.setCpu(fillMissing(p.getCpu(), defaultCpu()));
                p.setMemory(fillMissing(p.getMemory(), defaultMemory()));
                p.setDisk(fillMissing(p.getDisk(), defaultDisk()));
            }
            return;
        }
        repository.saveAll(List.of(
                new Policy("테라넷", "DB서버 정책",
                        new Threshold(70, 80, 90, 95), new Threshold(80, 85, 90, 95), new Threshold(80, 90, 95, 98)),
                new Policy("테라넷", "웹서버 정책",
                        new Threshold(85, 90, 95, 98), new Threshold(90, 92, 95, 98), new Threshold(85, 90, 95, 98)),
                new Policy("ABC", "ERP서버 정책",
                        new Threshold(75, 85, 90, 95), new Threshold(80, 88, 92, 96), new Threshold(80, 90, 95, 98))));
    }

    /**
     * 비어 있는 레벨만 채운 임계치 (값이 이미 다 있으면 같은 객체 그대로 → 불필요한 저장 없음).
     * 경고 = 주의와 위험의 중간, 장애 = 위험 + 5 (최대 100).
     */
    private static Threshold fillMissing(Threshold th, Threshold def) {
        // 비어 있음 = 사용자가 정책에서 그 지표를 꺼 둔 것 → 그대로 둔다
        if (th == null || th.isEmpty()) {
            return th;
        }
        if (th.getCaution() != null && th.getWarning() != null && th.getDanger() != null && th.getCritical() != null) {
            return th;
        }
        int caution = th.getCaution() != null ? th.getCaution() : def.getCaution();
        int danger = th.getDanger() != null ? th.getDanger() : def.getDanger();
        return new Threshold(caution,
                th.getWarning() != null ? th.getWarning() : (caution + danger) / 2,
                danger,
                th.getCritical() != null ? th.getCritical() : Math.min(100, danger + 5));
    }

    public List<Policy> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    /**
     * 화면 표시용 목록: 고객사별로 모아서 (고객사는 처음 등록된 순, 같은 고객사 안에서는 등록 순).
     * 새 정책을 추가해도 맨 아래가 아니라 자기 고객사 묶음 안에 보인다.
     * 판정용 순서(고객사 첫 번째 정책 = 기본)는 findAll() 그대로 쓴다.
     */
    public List<Policy> findAllGroupedByCompany() {
        Map<String, List<Policy>> byCompany = new LinkedHashMap<>();
        for (Policy p : findAll()) {
            byCompany.computeIfAbsent(p.getCompany(), k -> new ArrayList<>()).add(p);
        }
        return byCompany.values().stream().flatMap(List::stream).toList();
    }

    /**
     * 정책 입력값 검사 (화면에서도 막지만 저장 직전에 코드가 한 번 더 확인한다).
     * 문제가 없으면 null, 있으면 사용자에게 보여줄 문구.
     */
    public String validate(PolicyRequest req) {
        if (req.name() != null && req.name().isBlank()) {
            return "정책 명을 입력해 주세요.";
        }
        Map<String, Threshold> metrics = new LinkedHashMap<>();
        metrics.put("cpu", req.cpu());
        metrics.put("memory", req.memory());
        metrics.put("disk", req.disk());
        for (Map.Entry<String, Threshold> e : metrics.entrySet()) {
            if (e.getValue() != null && !e.getValue().isValid()) {
                return METRIC_KR.get(e.getKey()) + " 임계치는 0~100 사이 값을 주의 ≤ 경고 ≤ 위험 ≤ 장애 순으로 모두 입력해 주세요.";
            }
        }
        return null;
    }

    @Transactional
    public Policy add(PolicyRequest req) {
        Policy p = new Policy(
                orDefault(req.company(), "미지정"),
                orDefault(req.name(), "새 정책"),
                req.cpu() != null ? req.cpu() : defaultCpu(),
                req.memory() != null ? req.memory() : defaultMemory(),
                req.disk() != null ? req.disk() : defaultDisk());
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
    private static final Map<String, String> LEVEL_KR =
            Map.of("caution", "주의", "warning", "경고", "danger", "위험", "critical", "장애");

    /** 정책 이름 자리에 이 값이 오면 '해당 범위의 모든 정책' (고객사 자리도 같으면 전체 정책) */
    public static final String ALL = "*";

    /**
     * 임계치 변경. AI가 번역한 명령을 코드가 검증한 뒤 실제로 저장한다.
     * - 보통: 기업명·정책명이 포함되는 첫 번째 정책 1개
     * - 일괄: 정책명이 "*"이면 그 고객사의 모든 정책, 고객사도 "*"(또는 빈 값)이면 전체 정책
     * 결과 문구에는 그 정책으로 실제 판정되는 서버 수를 함께 적는다 (정책 1개 변경 = 서버 N대 적용).
     */
    @Transactional
    public List<ChangeResult> changeThreshold(String company, String policyName, String metric, String level, int value) {
        List<Policy> targets = findTargets(company, policyName);
        if (targets.isEmpty()) {
            return List.of(new ChangeResult(false, "'" + company + " " + policyName + "' 정책을 찾을 수 없습니다."));
        }
        Map<Long, List<String>> using = serversByPolicy();
        List<ChangeResult> results = new ArrayList<>();
        for (Policy target : targets) {
            ChangeResult r = changeOne(target, metric, level, value);
            if (r.ok()) {
                r = new ChangeResult(true, r.message() + appliedServers(using.getOrDefault(target.getId(), List.of())));
            }
            results.add(r);
        }
        return results;
    }

    /** 변경할 정책 찾기 ("*"는 일괄, 그 외는 고객사·이름이 포함되는 첫 번째 정책) */
    private List<Policy> findTargets(String company, String policyName) {
        String c = company == null ? "" : company.trim();
        String n = policyName == null ? "" : policyName.trim();
        if (ALL.equals(n)) {
            boolean allCompanies = c.isEmpty() || ALL.equals(c);
            return findAll().stream().filter(p -> allCompanies || p.getCompany().contains(c)).toList();
        }
        if (ALL.equals(c)) {
            // 모든 고객사에서 이 이름이 들어간 정책 전부 (예: 모든 고객사의 'DB서버 정책')
            return findAll().stream().filter(p -> p.getName().contains(n)).toList();
        }
        return findAll().stream()
                .filter(p -> p.getCompany().contains(c) && p.getName().contains(n))
                .findFirst()
                .map(List::of)
                .orElse(List.of());
    }

    /** " (적용 서버 3대: rocky-01, rocky-02, web-01)" — 이름은 최대 5개까지만 */
    private static String appliedServers(List<String> names) {
        if (names.isEmpty()) {
            return " (현재 이 정책으로 판정되는 서버 없음)";
        }
        String shown = String.join(", ", names.subList(0, Math.min(5, names.size())));
        return " (적용 서버 " + names.size() + "대: " + shown + (names.size() > 5 ? " 외" : "") + ")";
    }

    private ChangeResult changeOne(Policy target, String metric, String level, int value) {
        if (metric == null || !METRIC_KR.containsKey(metric) || !LEVEL_KR.containsKey(level)) {
            return new ChangeResult(false, "해당 항목을 찾을 수 없습니다.");
        }
        Threshold th = target.threshold(metric);
        if (th == null) {
            return new ChangeResult(false, "[" + target.getCompany() + "] " + target.getName() + "에서는 "
                    + METRIC_KR.get(metric) + " 알림을 사용하지 않도록 되어 있습니다. 알림 정책 화면에서 먼저 켜 주세요.");
        }
        if (value < 0 || value > 100) {
            return new ChangeResult(false, "임계치는 0~100% 범위여야 합니다. (요청값: " + value + "%)");
        }

        Integer old = th.get(level);
        Threshold updated = th.with(level, value);
        if (!updated.isOrdered()) {
            return new ChangeResult(false, "[" + target.getCompany() + "] " + target.getName() + " · "
                    + METRIC_KR.get(metric) + " " + LEVEL_KR.get(level) + " " + value
                    + "%는 레벨 순서(주의 ≤ 경고 ≤ 위험 ≤ 장애)에 맞지 않아 저장하지 않았습니다. (현재 "
                    + th.getCaution() + "/" + th.getWarning() + "/" + th.getDanger() + "/" + th.getCritical() + "%)");
        }
        switch (metric) {
            case "cpu" -> target.setCpu(updated);
            case "memory" -> target.setMemory(updated);
            default -> target.setDisk(updated);
        }
        repository.save(target);
        return new ChangeResult(true, "[" + target.getCompany() + "] " + target.getName() + " · "
                + METRIC_KR.get(metric) + " " + LEVEL_KR.get(level) + " " + old + "% → " + value + "%");
    }

    /** 정책 id → 그 정책으로 실제 판정되는 서버 이름들 (policyFor와 같은 규칙) */
    public Map<Long, List<String>> serversByPolicy() {
        List<Policy> all = findAll();
        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (MonitoredServer s : serverRepository.findAllByOrderByIdAsc()) {
            resolve(s.getPolicyId(), s.getCompany(), all)
                    .ifPresent(p -> out.computeIfAbsent(p.getId(), k -> new ArrayList<>()).add(s.label()));
        }
        return out;
    }

    /**
     * AI 명령으로 정책 추가. 값은 기본값(화면에서 '정책 추가'할 때와 같음)으로 시작하고,
     * 임계치를 함께 말했다면 AI가 뒤이어 보내는 변경 명령으로 바뀐다.
     */
    @Transactional
    public ChangeResult createPolicy(String company, String name) {
        String c = company == null ? "" : company.trim();
        String n = name == null ? "" : name.trim();
        if (c.isEmpty() || n.isEmpty() || ALL.equals(c) || ALL.equals(n)) {
            return new ChangeResult(false, "새 정책은 고객사와 정책 이름을 하나씩 정확히 말해 주세요.");
        }
        boolean exists = findAll().stream().anyMatch(p -> p.getCompany().equals(c) && p.getName().equals(n));
        if (exists) {
            return new ChangeResult(false, "[" + c + "] " + n + "은(는) 이미 있는 정책입니다.");
        }
        repository.save(new Policy(c, n, defaultCpu(), defaultMemory(), defaultDisk()));
        return new ChangeResult(true, "새 정책 추가: [" + c + "] " + n
                + " (기본값으로 생성, 서버 지정은 장비 목록 또는 정책 화면에서)");
    }

    /** 삭제 확인 정보. AI 명령으로는 바로 지우지 않고, 사용자가 버튼을 눌러야 기존 삭제 API로 지운다. */
    public record DeletePlan(boolean ok, String message, Long policyId) {}

    public DeletePlan planDelete(String company, String name) {
        String c = company == null ? "" : company.trim();
        String n = name == null ? "" : name.trim();
        if (ALL.equals(c) || ALL.equals(n) || (c.isEmpty() && n.isEmpty())) {
            return new DeletePlan(false, "정책 삭제는 한 번에 하나씩, 정책 이름을 정확히 말해 주세요.", null);
        }
        List<Policy> all = findAll();
        List<Policy> matched = all.stream().filter(p -> p.getCompany().contains(c) && p.getName().contains(n)).toList();
        if (matched.isEmpty()) {
            return new DeletePlan(false, "'" + c + " " + n + "' 정책을 찾을 수 없습니다.", null);
        }
        if (matched.size() > 1) {
            return new DeletePlan(false, "여러 정책이 일치합니다: "
                    + String.join(", ", matched.stream().map(p -> "[" + p.getCompany() + "] " + p.getName()).toList())
                    + " — 어느 정책인지 이름을 정확히 말해 주세요.", null);
        }
        if (all.size() == 1) {
            return new DeletePlan(false, "마지막 남은 정책은 삭제할 수 없습니다. (판정 기준이 없어집니다)", null);
        }
        Policy target = matched.get(0);
        // 삭제 후 각 서버가 어떤 정책으로 판정될지 (policyFor와 같은 규칙으로 미리 계산)
        List<Policy> rest = all.stream().filter(p -> !p.getId().equals(target.getId())).toList();
        Map<String, List<String>> moveTo = new LinkedHashMap<>();
        for (MonitoredServer s : serverRepository.findAllByOrderByIdAsc()) {
            Optional<Policy> now = resolve(s.getPolicyId(), s.getCompany(), all);
            if (now.isPresent() && now.get().getId().equals(target.getId())) {
                Long keep = target.getId().equals(s.getPolicyId()) ? null : s.getPolicyId();
                resolve(keep, s.getCompany(), rest).ifPresent(p -> moveTo
                        .computeIfAbsent("[" + p.getCompany() + "] " + p.getName(), k -> new ArrayList<>()).add(s.label()));
            }
        }
        StringBuilder msg = new StringBuilder("정책 삭제: [" + target.getCompany() + "] " + target.getName() + " — 삭제할까요?");
        if (moveTo.isEmpty()) {
            msg.append("\n현재 이 정책으로 판정되는 서버는 없습니다.");
        } else {
            moveTo.forEach((pol, names) -> msg.append("\n· 서버 ").append(names.size()).append("대(")
                    .append(String.join(", ", names.subList(0, Math.min(5, names.size()))))
                    .append(names.size() > 5 ? " 외" : "").append(")는 삭제 후 ").append(pol).append("으로 판정됩니다."));
        }
        return new DeletePlan(true, msg.toString(), target.getId());
    }

    public record AssignResult(boolean ok, int assigned, int released, List<String> skipped) {}

    /**
     * 정책 팝업의 '장비' 탭: 체크한 서버에 이 정책을 지정하고, 체크를 푼 서버는 지정을 해제(→ 기본 정책)한다.
     * 서버는 자기 고객사의 정책만 쓸 수 있으므로, 다른 고객사 서버는 건너뛰고 알려준다.
     * 고객사가 아직 없는 서버는 이 정책의 고객사로 함께 지정한다.
     */
    @Transactional
    public AssignResult assignServers(Long policyId, List<Long> serverIds) {
        Policy policy = repository.findById(policyId).orElse(null);
        if (policy == null) {
            return new AssignResult(false, 0, 0, List.of());
        }
        Set<Long> wanted = new HashSet<>(serverIds == null ? List.of() : serverIds);
        int assigned = 0;
        int released = 0;
        List<String> skipped = new ArrayList<>();
        for (MonitoredServer s : serverRepository.findAllByOrderByIdAsc()) {
            boolean has = policyId.equals(s.getPolicyId());
            if (wanted.contains(s.getId()) && !has) {
                if (s.getCompany() != null && !s.getCompany().equals(policy.getCompany())) {
                    skipped.add(s.label() + "(고객사: " + s.getCompany() + ")");
                    continue;
                }
                s.setCompany(policy.getCompany());
                s.setPolicyId(policyId);
                assigned++;
            } else if (!wanted.contains(s.getId()) && has) {
                s.setPolicyId(null);
                released++;
            }
        }
        return new AssignResult(true, assigned, released, skipped);
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
        return resolve(policyId, company, findAll());
    }

    private static Optional<Policy> resolve(Long policyId, String company, List<Policy> all) {
        if (policyId != null) {
            Optional<Policy> assigned = all.stream().filter(p -> policyId.equals(p.getId())).findFirst();
            if (assigned.isPresent()) {
                return assigned;
            }
        }
        if (company != null) {
            Optional<Policy> companyDefault = all.stream()
                    .filter(p -> company.equals(p.getCompany()))
                    .findFirst();
            if (companyDefault.isPresent()) {
                return companyDefault;
            }
        }
        return all.stream().findFirst();
    }

    /** 고객사 목록 (정책에 등록된 고객사, 등록 순) */
    public List<String> companies() {
        return findAll().stream().map(Policy::getCompany).distinct().toList();
    }

    /** 심각한 순서 (판정·대표 상태 계산에 공통 사용) */
    private static final List<String> LEVEL_KEYS = List.of("critical", "danger", "warning", "caution");

    /** 값이 넘은 가장 높은 레벨 (장애 → 위험 → 경고 → 주의 순으로 확인, 비어 있는 레벨은 건너뜀) */
    public static String judge(double value, Threshold th) {
        if (th == null) {
            return "정상"; // 이 정책에서 사용하지 않는 지표
        }
        for (String key : LEVEL_KEYS) {
            Integer limit = th.get(key);
            if (limit != null && value >= limit) {
                return LEVEL_KR.get(key);
            }
        }
        return "정상";
    }

    private static final List<String> CORE_METRICS = List.of("cpu", "memory", "disk");

    /** 서버가 보낸 CPU/메모리/디스크 값을 주어진 정책으로 판정 (정책이 없으면 모두 정상) */
    public Map<String, Map<String, Object>> judgeAll(Map<String, Double> core, Policy pol) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String key : CORE_METRICS) {
            double value = core.getOrDefault(key, 0.0);
            Threshold th = pol != null ? pol.threshold(key) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", Math.round(value * 10) / 10.0);
            m.put("status", judge(value, th));
            m.put("exceeded", exceeded(value, th));
            result.put(key, m);
        }
        return result;
    }

    /**
     * 값이 넘은 모든 레벨 (주의 → 장애 순).
     * AiWACS처럼 레벨마다 알람이 따로 생기므로, 장애일 때는 주의·경고·위험·장애 기준을 모두 넘은 것으로 본다.
     */
    public static List<Map<String, Object>> exceeded(double value, Threshold th) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (th == null) {
            return out;
        }
        for (String key : List.of("caution", "warning", "danger", "critical")) {
            Integer limit = th.get(key);
            if (limit != null && value >= limit) {
                out.add(Map.of("level", LEVEL_KR.get(key), "threshold", limit));
            }
        }
        return out;
    }

    /** 판정 결과 중 가장 심각한 상태 (서버 한 대의 대표 상태) */
    public static String worst(Map<String, Map<String, Object>> judged) {
        List<Object> statuses = judged.values().stream().map(m -> m.get("status")).toList();
        for (String key : LEVEL_KEYS) {
            if (statuses.contains(LEVEL_KR.get(key))) {
                return LEVEL_KR.get(key);
            }
        }
        return "정상";
    }

    private static String orDefault(String v, String def) {
        return v != null ? v : def;
    }
}
