package com.sysone.aiwacs.server;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sysone.aiwacs.policy.Policy;
import com.sysone.aiwacs.policy.PolicyService;

/**
 * Agent가 보낸 지표를 받아 서버별 최신 상태를 보관한다.
 * 서버 목록은 DB에, 최신 지표는 메모리에 둔다 (몇 초마다 바뀌는 값이라 DB에 매번 쓰지 않음).
 */
@Service
public class ServerService {

    /** 이 시간 동안 지표가 안 오면 오프라인으로 본다 */
    private static final Duration OFFLINE_AFTER = Duration.ofSeconds(10);

    public record Snapshot(AgentReport report, Instant receivedAt) {}

    private final ServerRepository repository;
    private final PolicyService policyService;
    private final Map<Long, Snapshot> latest = new ConcurrentHashMap<>();

    public ServerService(ServerRepository repository, PolicyService policyService) {
        this.repository = repository;
        this.policyService = policyService;
    }

    /** 서버의 최신 지표를 "그 서버에 적용된 정책"으로 판정 */
    public Optional<Map<String, Map<String, Object>>> judge(Long serverId) {
        Optional<MonitoredServer> server = repository.findById(serverId);
        Optional<Snapshot> snap = latest(serverId);
        if (server.isEmpty() || snap.isEmpty()) {
            return Optional.empty();
        }
        Policy policy = policyOf(server.get()).orElse(null);
        return Optional.of(policyService.judgeAll(snap.get().report().core(), policy));
    }

    private Optional<Policy> policyOf(MonitoredServer s) {
        return policyService.policyFor(s.getPolicyId(), s.getCompany());
    }

    public record UpdateResult(boolean ok, int status, String error) {}

    /**
     * 서버 정보 수정 (표시 이름 / 고객사 / 적용 정책).
     * 정책은 서버와 같은 고객사의 정책만 지정할 수 있다. 고객사를 바꾸면 맞지 않는 정책은 기본 정책으로 되돌린다.
     */
    @Transactional
    public UpdateResult update(Long serverId, String displayName, String company, Long policyId) {
        MonitoredServer server = repository.findById(serverId).orElse(null);
        if (server == null) {
            return new UpdateResult(false, 404, "server not found");
        }
        String name = blankToNull(displayName);
        if (name != null && name.length() > 50) {
            return new UpdateResult(false, 400, "표시 이름은 50자 이하로 입력해 주세요.");
        }
        String comp = blankToNull(company);
        if (comp != null && !policyService.companies().contains(comp)) {
            return new UpdateResult(false, 400, "등록되지 않은 고객사입니다.");
        }
        if (policyId != null) {
            Policy p = policyService.findById(policyId).orElse(null);
            if (p == null) {
                return new UpdateResult(false, 400, "정책을 찾을 수 없습니다.");
            }
            if (comp != null && !comp.equals(p.getCompany())) {
                return new UpdateResult(false, 400, "서버의 고객사(" + comp + ")에 속한 정책만 지정할 수 있습니다.");
            }
        }
        server.setDisplayName(name);
        server.setCompany(comp);
        server.setPolicyId(policyId);
        return new UpdateResult(true, 200, null);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** Agent 지표 수신. 처음 보는 서버면 자동 등록, 호스트명·IP·OS가 바뀌었을 때만 DB 갱신. */
    @Transactional
    public MonitoredServer receive(AgentReport report, String remoteIp) {
        MonitoredServer server = repository.findByName(report.serverName())
                .orElseGet(() -> repository.save(new MonitoredServer(report.serverName())));
        if (!Objects.equals(server.getHostname(), report.hostname())
                || !Objects.equals(server.getIp(), remoteIp)
                || !Objects.equals(server.getOs(), report.os())) {
            server.setHostname(report.hostname());
            server.setIp(remoteIp);
            server.setOs(report.os());
        }
        latest.put(server.getId(), new Snapshot(report, Instant.now()));
        return server;
    }

    public List<MonitoredServer> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    public Optional<Snapshot> latest(Long serverId) {
        return Optional.ofNullable(latest.get(serverId));
    }

    public boolean isOnline(Long serverId) {
        Snapshot s = latest.get(serverId);
        return s != null && s.receivedAt().isAfter(Instant.now().minus(OFFLINE_AFTER));
    }

    public Optional<MonitoredServer> findById(Long id) {
        return repository.findById(id);
    }

    /** 화면용 서버 정보 (DB 정보 + 온라인 여부 + 마지막 수신 시각) */
    public Map<String, Object> summary(MonitoredServer s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.label());            // 화면 표시 이름
        m.put("agentName", s.getName());     // Agent ID (agent.properties의 server.name)
        m.put("displayName", s.getDisplayName());
        m.put("company", s.getCompany());
        m.put("hostname", s.getHostname());
        m.put("ip", s.getIp());
        m.put("os", s.getOs());
        m.put("online", isOnline(s.getId()));
        m.put("lastSeen", latest(s.getId()).map(Snapshot::receivedAt).orElse(null));

        // 적용 정책 (지정이 없거나 삭제된 정책이면 기본 정책)
        Policy policy = policyOf(s).orElse(null);
        boolean assigned = s.getPolicyId() != null && policy != null && s.getPolicyId().equals(policy.getId());
        m.put("policyId", assigned ? policy.getId() : null);
        m.put("policyName", policy == null ? null
                : "[" + policy.getCompany() + "] " + policy.getName() + (assigned ? "" : " (기본)"));
        return m;
    }
}
