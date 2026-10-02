package com.sysone.aiwacs.action;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sysone.aiwacs.server.AgentReport;
import com.sysone.aiwacs.server.MonitoredServer;
import com.sysone.aiwacs.server.ServerService;

/**
 * 조치 실행 (설계 원칙: AI는 제안만, 실행은 사람이 승인해야만).
 *
 * AI는 이 서비스를 부르지 않는다. 화면에서 사람이 [실행]을 눌렀을 때만 명령이 만들어진다.
 * 명령은 AiWACS가 Agent에 직접 접속해 보내는 것이 아니라, Agent가 지표를 보낼 때 응답에 실어 가져간다
 * (Agent 쪽에 열린 포트가 필요 없음).
 *
 * 안전장치:
 * - 조치는 정상 종료(SIGTERM) / 우선순위 낮추기(renice +10) 두 가지만
 * - 그 서버 Agent가 조치를 허용(action.enabled=true)했을 때만
 * - 승인 시점에 그 프로세스(PID + 이름 + 시작 시각)가 실제로 실행 중이어야 함 → Agent가 실행 직전에 한 번 더 확인
 * - 시스템 핵심 프로세스는 버튼 자체를 보여주지 않고, 요청이 와도 거절
 * - 30초 안에 Agent가 가져가지 않으면 만료 (나중에 엉뚱한 때 실행되지 않게)
 * - 시뮬레이션 모드(action.simulation=true)면 Agent에 보내지 않고 기록만
 */
@Service
public class ActionService {

    public static final Set<String> ACTIONS = Set.of("terminate", "renice");

    /** Agent의 ActionExecutor.PROTECTED와 같은 목록 */
    static final Set<String> PROTECTED = Set.of(
            "systemd", "init", "kthreadd", "sshd", "dbus-daemon", "dbus-broker", "systemd-journal",
            "systemd-logind", "systemd-udevd", "NetworkManager", "chronyd", "auditd", "rsyslogd",
            "crond", "polkitd", "firewalld", "agetty", "login", "bash", "sh");

    private static final Duration PICKUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX = 200;

    private final ServerService servers;
    private final boolean simulation;
    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentLinkedDeque<ActionCommand> all = new ConcurrentLinkedDeque<>();

    public ActionService(ServerService servers, @Value("${action.simulation:false}") boolean simulation) {
        this.servers = servers;
        this.simulation = simulation;
    }

    public boolean isSimulation() {
        return simulation;
    }

    public record Result(boolean ok, String error, ActionCommand command) {}

    /** 이 프로세스에 조치 버튼을 보여줘도 되는지. 안 되면 이유(화면 안내용), 되면 null */
    public String blockedReason(Long serverId, AgentReport.Proc p) {
        if (p.pid() == null || p.start() == null) {
            return "Agent가 PID를 보내지 않습니다 (Agent를 새 버전으로 바꿔 주세요).";
        }
        if (p.pid() <= 2 || PROTECTED.contains(p.name())) {
            return "'" + p.name() + "'은(는) 서버 운영에 필요한 보호 프로세스라 조치할 수 없습니다.";
        }
        if (!simulation && !agentAllows(serverId)) {
            return "이 서버의 Agent에서 조치 실행이 꺼져 있습니다 (agent.properties에 action.enabled=true).";
        }
        return null;
    }

    private boolean agentAllows(Long serverId) {
        return servers.latest(serverId).map(s -> Boolean.TRUE.equals(s.report().actionEnabled())).orElse(false);
    }

    /** 사람이 [실행]을 눌렀을 때. 지금 그 프로세스가 실제로 실행 중인지 최신 지표로 확인한 뒤 명령을 만든다. */
    public Result request(Long serverId, String action, long pid, String name, long start, String by, String reason) {
        if (!ACTIONS.contains(action)) {
            return new Result(false, "허용하지 않은 조치입니다.", null);
        }
        MonitoredServer server = serverId == null ? null : servers.findById(serverId).orElse(null);
        if (server == null) {
            return new Result(false, "서버를 찾을 수 없습니다.", null);
        }
        if (!servers.isOnline(serverId)) {
            return new Result(false, "서버가 오프라인이라 조치를 보낼 수 없습니다.", null);
        }
        Optional<AgentReport.Proc> now = servers.latest(serverId).orElseThrow().report().procs().stream()
                .filter(p -> p.pid() != null && p.pid() == pid && name.equals(p.name())
                        && p.start() != null && Math.abs(p.start() - start) <= 2000)
                .findFirst();
        if (now.isEmpty()) {
            return new Result(false, name + "(PID " + pid + ")가 지금은 실행 중이 아닙니다 (이미 끝났거나 다른 프로세스로 바뀜). 진단을 다시 실행해 주세요.", null);
        }
        String blocked = blockedReason(serverId, now.get());
        if (blocked != null) {
            return new Result(false, blocked, null);
        }
        boolean busy = all.stream().anyMatch(c -> c.getServerId().equals(serverId)
                && (c.getStatus() == ActionCommand.Status.PENDING || c.getStatus() == ActionCommand.Status.SENT));
        if (busy) {
            return new Result(false, "이 서버에 아직 끝나지 않은 조치가 있습니다. 결과를 확인한 뒤 다시 시도해 주세요.", null);
        }

        Instant t = Instant.now();
        ActionCommand c = new ActionCommand(seq.incrementAndGet(), serverId, server.label(), action, pid, name,
                start, by == null || by.isBlank() ? "aiwacs" : by.strip(), reason, t);
        if (simulation) {
            c.finish(ActionCommand.Status.SIMULATED,
                    "시뮬레이션 모드라 실제로 실행하지 않았습니다 (" + ActionCommand.actionKr(action) + " 요청만 기록).", t);
        }
        all.addFirst(c);
        while (all.size() > MAX) {
            all.removeLast();
        }
        return new Result(true, null, c);
    }

    /** Agent 지표 수신 시: 그 서버에 대기 중인 명령을 넘겨주고 '전달됨'으로 표시 */
    public List<Map<String, Object>> takeCommands(Long serverId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Instant now = Instant.now();
        for (ActionCommand c : all) {
            if (c.getServerId().equals(serverId) && c.getStatus() == ActionCommand.Status.PENDING) {
                c.sent(now);
                out.add(c.toAgent());
            }
        }
        return out;
    }

    /** Agent가 보낸 실행 결과 반영 */
    public void applyResults(Long serverId, List<Map<String, Object>> results) {
        if (results == null) {
            return;
        }
        Instant now = Instant.now();
        for (Map<String, Object> r : results) {
            long id;
            try {
                id = Long.parseLong(String.valueOf(r.get("id")));
            } catch (NumberFormatException e) {
                continue;
            }
            for (ActionCommand c : all) {
                if (c.getId() == id && c.getServerId().equals(serverId) && c.getStatus() == ActionCommand.Status.SENT) {
                    boolean ok = Boolean.parseBoolean(String.valueOf(r.get("ok")));
                    c.finish(ok ? ActionCommand.Status.DONE : ActionCommand.Status.FAILED,
                            String.valueOf(r.get("message")), now);
                }
            }
        }
    }

    /** 너무 오래 기다린 명령 정리 */
    @Scheduled(fixedDelay = 5000)
    public void expire() {
        Instant now = Instant.now();
        for (ActionCommand c : all) {
            if (c.getStatus() == ActionCommand.Status.PENDING && c.getCreatedAt().plus(PICKUP_TIMEOUT).isBefore(now)) {
                c.finish(ActionCommand.Status.EXPIRED, "30초 안에 Agent가 명령을 가져가지 않아 취소했습니다 (Agent 연결 확인 필요).", now);
            } else if (c.getStatus() == ActionCommand.Status.SENT && c.getSentAt().plus(RESULT_TIMEOUT).isBefore(now)) {
                c.finish(ActionCommand.Status.FAILED, "Agent에 전달했지만 결과를 받지 못했습니다. 서버에서 직접 확인해 주세요.", now);
            }
        }
    }

    public Optional<ActionCommand> find(long id) {
        return all.stream().filter(c -> c.getId() == id).findFirst();
    }

    /** 조치 이력 (최신순). serverId가 있으면 그 서버만 */
    public List<Map<String, Object>> list(Long serverId) {
        return all.stream().filter(c -> serverId == null || c.getServerId().equals(serverId))
                .map(ActionCommand::toMap).toList();
    }

    /** since 이후 그 서버의 조치 (AI 처리 기록 초안용) */
    public List<Map<String, Object>> since(Long serverId, Instant since) {
        return all.stream().filter(c -> c.getServerId().equals(serverId) && !c.getCreatedAt().isBefore(since))
                .map(ActionCommand::toMap).toList();
    }
}
