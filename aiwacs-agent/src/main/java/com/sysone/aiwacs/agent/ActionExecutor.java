package com.sysone.aiwacs.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * AiWACS 화면에서 사람이 승인한 조치를 이 서버에서 실행한다.
 *
 * 안전장치 (AiWACS 본체도 같은 검사를 하지만, 실제로 실행하는 쪽인 Agent가 마지막으로 한 번 더 확인한다):
 * - agent.properties의 action.enabled=true 일 때만 실행 (기본 꺼짐)
 * - 조치는 두 가지만: 정상 종료(SIGTERM) / 우선순위 낮추기(renice +10). 강제 종료·셸 명령은 받지 않는다
 * - PID + 이름 + 시작 시각이 승인할 때 본 프로세스와 모두 같아야 실행 (그사이 PID가 다른 프로세스에 재사용됐으면 거절)
 * - 시스템 핵심 프로세스(PID 1, 커널 스레드, systemd·sshd 등)와 Agent 자신은 거절
 */
public class ActionExecutor {

    /** 끄거나 건드리면 서버 접속·운영이 끊길 수 있는 프로세스 (본체 ActionService와 같은 목록) */
    static final Set<String> PROTECTED = Set.of(
            "systemd", "init", "kthreadd", "sshd", "dbus-daemon", "dbus-broker", "systemd-journal",
            "systemd-logind", "systemd-udevd", "NetworkManager", "chronyd", "auditd", "rsyslogd",
            "crond", "polkitd", "firewalld", "agetty", "login", "bash", "sh");

    private final OperatingSystem os = new SystemInfo().getOperatingSystem();

    /** 명령 하나를 실행하고 결과를 돌려준다. 결과는 다음 지표 전송에 실려 AiWACS로 간다. */
    public Map<String, Object> execute(Map<String, Object> cmd, boolean enabled) {
        long id = toLong(cmd.get("id"));
        String action = String.valueOf(cmd.get("action"));
        int pid = (int) toLong(cmd.get("pid"));
        String name = String.valueOf(cmd.get("name"));
        long start = toLong(cmd.get("start"));

        if (!enabled) {
            return result(id, false, "이 서버의 Agent에서 조치 실행이 꺼져 있습니다 (agent.properties의 action.enabled=true 필요).");
        }
        if (!"terminate".equals(action) && !"renice".equals(action)) {
            return result(id, false, "허용하지 않은 조치입니다: " + action);
        }

        OSProcess p = os.getProcess(pid);
        if (p == null) {
            return result(id, true, "PID " + pid + "(" + name + ")는 이미 종료되어 있어 조치할 필요가 없었습니다.");
        }
        // 승인할 때 본 프로세스와 같은지: 이름 + 시작 시각 (PID 재사용 방지)
        if (!Collector.displayName(p).equals(name) || Math.abs(p.getStartTime() - start) > 2000) {
            return result(id, false, "PID " + pid + "가 승인 당시의 '" + name + "'와 다른 프로세스로 바뀌어 실행하지 않았습니다.");
        }
        String why = protectedReason(p);
        if (why != null) {
            return result(id, false, why);
        }

        try {
            return "terminate".equals(action) ? terminate(id, pid, name) : renice(id, pid, name);
        } catch (Exception e) {
            return result(id, false, "실행 중 오류: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private String protectedReason(OSProcess p) {
        if (p.getProcessID() <= 2 || p.getParentProcessID() == 2) {
            return "시스템 핵심 프로세스(PID " + p.getProcessID() + ")는 조치할 수 없습니다.";
        }
        if (p.getProcessID() == ProcessHandle.current().pid()) {
            return "Agent 자신은 조치할 수 없습니다.";
        }
        if (PROTECTED.contains(p.getName())) {
            return "'" + p.getName() + "'은(는) 서버 운영에 필요한 보호 프로세스라 조치할 수 없습니다.";
        }
        return null;
    }

    /** 정상 종료: SIGTERM을 보내 프로세스가 스스로 정리하고 끝나게 한다. 5초 동안 종료되는지 지켜본다. */
    private Map<String, Object> terminate(long id, int pid, String name) throws InterruptedException {
        ProcessHandle h = ProcessHandle.of(pid).orElse(null);
        if (h == null) {
            return result(id, true, name + "(PID " + pid + ")는 이미 종료되어 있었습니다.");
        }
        if (!h.destroy()) { // 유닉스에서 destroy() = SIGTERM
            return result(id, false, name + "(PID " + pid + ")에 종료 요청을 보내지 못했습니다 (권한 부족 가능성).");
        }
        for (int i = 0; i < 10 && h.isAlive(); i++) {
            TimeUnit.MILLISECONDS.sleep(500);
        }
        return h.isAlive()
                ? result(id, false, name + "(PID " + pid + ")에 종료 요청을 보냈지만 5초 안에 끝나지 않았습니다. 강제 종료는 서버에서 직접 판단해 주세요.")
                : result(id, true, name + "(PID " + pid + ")를 정상 종료했습니다.");
    }

    /** 우선순위 낮추기: 프로세스는 살려두고 nice 값을 10 올려 다른 프로세스에 CPU를 양보하게 한다. */
    private Map<String, Object> renice(long id, int pid, String name) throws Exception {
        // 셸을 거치지 않고 인자를 따로 넘긴다 (명령어 끼워넣기 방지)
        Process proc = new ProcessBuilder("renice", "-n", "10", "-p", String.valueOf(pid))
                .redirectErrorStream(true).start();
        String out = new String(proc.getInputStream().readAllBytes()).strip();
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            return result(id, false, "renice가 응답하지 않았습니다.");
        }
        return proc.exitValue() == 0
                ? result(id, true, name + "(PID " + pid + ")의 우선순위를 낮췄습니다 (nice +10). 프로세스는 계속 실행됩니다.")
                : result(id, false, "우선순위를 바꾸지 못했습니다: " + out);
    }

    private static Map<String, Object> result(long id, boolean ok, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ok", ok);
        m.put("message", message);
        return m;
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
