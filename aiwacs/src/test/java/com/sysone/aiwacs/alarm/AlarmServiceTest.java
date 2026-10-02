package com.sysone.aiwacs.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sysone.aiwacs.policy.PolicyService;
import com.sysone.aiwacs.policy.Threshold;
import com.sysone.aiwacs.server.MonitoredServer;
import com.sysone.aiwacs.server.ServerService;

/**
 * 실제 AiWACS 부하 실측(2026-10-01, server1)과 같은 흐름을 재현:
 * CPU 26% → 44% → 100% → 정상 복귀. 정책 CPU 30/40/50/60.
 */
class AlarmServiceTest {

    private final ServerService servers = mock(ServerService.class);
    private final AlarmService engine = new AlarmService(servers);
    private final Threshold cpu = new Threshold(30, 40, 50, 60);

    AlarmServiceTest() {
        MonitoredServer s = mock(MonitoredServer.class);
        when(s.getId()).thenReturn(1L);
        when(s.label()).thenReturn("server1");
        when(servers.findAll()).thenReturn(List.of(s));
        when(servers.isOnline(any())).thenReturn(true);
    }

    private void cpuAt(double value) {
        Map<String, Map<String, Object>> judged = Map.of("cpu", Map.of(
                "value", value,
                "status", PolicyService.judge(value, cpu),
                "exceeded", PolicyService.exceeded(value, cpu)));
        when(servers.judge(any())).thenReturn(Optional.of(judged));
        engine.evaluate();
    }

    private long activeAt(String level) {
        return engine.activeAlarms().stream().filter(a -> level.equals(a.get("level"))).count();
    }

    @Test
    void 레벨마다_알람이_따로_쌓이고_사건_하나로_묶인다() {
        cpuAt(26);
        assertEquals(0, engine.activeAlarms().size(), "주의(30) 미만이면 알람 없음");

        cpuAt(44);
        assertEquals(3, activeAt("주의"), "CPU/CPU Core/CPU User × 주의");
        assertEquals(3, activeAt("경고"), "44%는 경고(40)도 넘음");
        assertEquals(6, engine.activeAlarms().size());

        cpuAt(100);
        assertEquals(12, engine.activeAlarms().size(), "지표 3 × 레벨 4 = 12건 (주의·경고 알람은 그대로 열려 있음)");

        List<Map<String, Object>> events = engine.events();
        assertEquals(1, events.size(), "12건이 사건 1건으로 묶임");
        assertEquals("장애", events.get(0).get("level"));
        assertEquals(12, events.get(0).get("alarmCount"));

        cpuAt(45);
        assertEquals(6, engine.activeAlarms().size(), "위험·장애 알람만 해제되고 주의·경고는 남음");

        cpuAt(5);
        assertEquals(0, engine.activeAlarms().size(), "정상 복귀 시 모두 해제");
        assertEquals(12, engine.history().size(), "이력에는 12건이 남음 (같은 알람이 다시 생기지 않음)");
    }

    @Test
    void 처리와_해제는_따로_기록된다() {
        cpuAt(44);
        List<Long> ids = engine.activeAlarms().stream().map(a -> (Long) a.get("id")).toList();
        assertEquals(6, engine.handle(ids, Alarm.ProcessStatus.MAINTENANCE, "운영자", "stress-ng 종료 예정"));
        assertEquals(6, engine.activeAlarms().size(), "처리해도 지표가 기준 위면 계속 발생 중 (신호를 가리지 않음)");

        cpuAt(5);
        Map<String, Object> first = engine.history().getFirst();
        assertEquals("RESOLVED", first.get("status"), "조치 중으로 처리한 알람도 값이 내려가면 해제 시각이 남음");
        assertEquals("MAINTENANCE", first.get("processStatus"));

        engine.handle(ids, Alarm.ProcessStatus.COMPLETE, "운영자", "종료 후 정상 복귀");
        assertEquals(12, engine.handled().size(), "알람 6건 × 기록 2번(조치 중 → 완료)");
        assertEquals("COMPLETE", engine.history().getFirst().get("processStatus"));
    }
}
