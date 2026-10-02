package com.sysone.aiwacs.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sysone.aiwacs.alarm.AlarmService;
import com.sysone.aiwacs.server.AgentReport;
import com.sysone.aiwacs.server.MonitoredServer;
import com.sysone.aiwacs.server.ServerService;

/** 조치 안전장치: 사람이 승인한 실행 중 프로세스만, 보호 프로세스·PID 재사용·Agent 미허용은 거절 */
class ActionServiceTest {

    private static final long START = 1_790_000_000_000L;

    private final ServerService servers = mock(ServerService.class);
    private final AlarmService alarms = mock(AlarmService.class);

    private ActionService serviceWith(boolean agentEnabled, boolean simulation) {
        MonitoredServer s = mock(MonitoredServer.class);
        when(s.label()).thenReturn("server1");
        when(servers.findById(1L)).thenReturn(Optional.of(s));
        when(servers.isOnline(1L)).thenReturn(true);
        AgentReport report = new AgentReport("server1", "h", "Linux", Map.of("cpu", 100.0), List.of(
                new AgentReport.Proc("stress-ng", 99.0, 0.5, 1234L, START, "root"),
                new AgentReport.Proc("sshd", 0.1, 0.4, 800L, START, "root")),
                List.of(), Map.of(), Map.of(), Map.of(), List.of(), agentEnabled, List.of());
        when(servers.latest(1L)).thenReturn(Optional.of(new ServerService.Snapshot(report, Instant.now())));
        when(alarms.recordAction(eq(1L), anyString(), anyString())).thenReturn(6);
        return new ActionService(servers, alarms, simulation);
    }

    @Test
    void 승인한_명령은_Agent가_한_번만_가져가고_결과가_반영된다() {
        ActionService svc = serviceWith(true, false);
        ActionService.Result r = svc.request(1L, "terminate", 1234, "stress-ng", START, "운영자", "CPU 100%");
        assertTrue(r.ok(), r.error());
        assertEquals(ActionCommand.Status.PENDING, r.command().getStatus());

        List<Map<String, Object>> cmds = svc.takeCommands(1L);
        assertEquals(1, cmds.size());
        assertEquals(1234L, cmds.get(0).get("pid"));
        assertTrue(svc.takeCommands(1L).isEmpty(), "같은 명령을 두 번 보내지 않음");

        svc.applyResults(1L, List.of(Map.of("id", r.command().getId(), "ok", true, "message", "정상 종료했습니다.")));
        assertEquals(ActionCommand.Status.DONE, r.command().getStatus());
        assertEquals(6, r.command().toMap().get("recordedAlarms"), "결과가 그 서버의 처리 대기 알림 6건에 처리 기록으로 남음");
        verify(alarms).recordAction(1L, "운영자", "[조치] stress-ng(PID 1234) 정상 종료 → 성공: 정상 종료했습니다.");
    }

    @Test
    void 보호_프로세스와_바뀐_프로세스와_허용_안된_Agent는_거절() {
        ActionService svc = serviceWith(true, false);
        assertFalse(svc.request(1L, "terminate", 800, "sshd", START, null, null).ok(), "sshd는 보호 프로세스");
        assertFalse(svc.request(1L, "terminate", 1234, "stress-ng", START + 60_000, null, null).ok(), "시작 시각이 다르면 다른 프로세스");
        assertFalse(svc.request(1L, "kill -9", 1234, "stress-ng", START, null, null).ok(), "허용한 조치(종료·우선순위)만");

        ActionService off = serviceWith(false, false);
        AgentReport.Proc stress = new AgentReport.Proc("stress-ng", 99.0, 0.5, 1234L, START, "root");
        assertNotNull(off.blockedReason(1L, stress), "Agent가 action.enabled=false면 버튼을 막음");
        assertFalse(off.request(1L, "terminate", 1234, "stress-ng", START, null, null).ok());
    }

    @Test
    void 시뮬레이션이면_Agent에_보내지_않고_기록만() {
        ActionService svc = serviceWith(false, true);
        assertNull(svc.blockedReason(1L, new AgentReport.Proc("stress-ng", 99.0, 0.5, 1234L, START, "root")));
        ActionService.Result r = svc.request(1L, "renice", 1234, "stress-ng", START, null, null);
        assertTrue(r.ok());
        assertEquals(ActionCommand.Status.SIMULATED, r.command().getStatus());
        assertTrue(svc.takeCommands(1L).isEmpty());
    }

    @Test
    void 끝나지_않은_조치가_있으면_새_조치를_받지_않음() {
        ActionService svc = serviceWith(true, false);
        assertTrue(svc.request(1L, "renice", 1234, "stress-ng", START, null, null).ok());
        assertFalse(svc.request(1L, "terminate", 1234, "stress-ng", START, null, null).ok());
    }
}
