package com.sysone.aiwacs.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 부하 시연과 같은 흐름: 10:00~10:09 이력 중 10:03에 stress-ng가 새로 시작해 10:07에 끝남.
 * java는 원래부터 메모리를 많이 쓰고, sshd는 거의 0%.
 */
class ProcessTrendTest {

    private static final Instant T0 = Instant.parse("2026-10-02T01:00:00Z");

    private static Instant min(int m) {
        return T0.plusSeconds(60L * m);
    }

    @Test
    void 새로_시작한_프로세스가_첫_번째_원인_후보() {
        List<ProcessHistory> rows = new ArrayList<>();
        long stressStart = min(3).plusSeconds(20).toEpochMilli();
        long javaStart = T0.minusSeconds(3600).toEpochMilli();
        for (int m = 0; m < 10; m++) {
            rows.add(new ProcessHistory(1L, min(m), "java", 900L, javaStart, 3.0, 22.0));
            rows.add(new ProcessHistory(1L, min(m), "sshd", 800L, javaStart, 0.1, 0.5));
            if (m >= 3 && m <= 7) {
                rows.add(new ProcessHistory(1L, min(m), "stress-ng", 1234L, stressStart, m == 3 ? 40 : 100, 1.0));
            }
        }

        List<ProcessTrend.Suspect> s = ProcessTrend.analyze(rows, 5);

        assertEquals("stress-ng", s.get(0).name());
        assertEquals("NEW", s.get(0).type());
        assertEquals(100.0, s.get(0).peakCpu());
        assertTrue(s.get(0).endedInWindow(), "10:07에 끝나서 구간 끝(10:09)보다 먼저 사라짐");
        assertEquals("java", s.get(1).name());
        assertEquals("STEADY", s.get(1).type(), "원래부터 메모리가 높았던 프로세스는 '원래 높음'");
        assertFalse(s.stream().anyMatch(x -> x.name().equals("sshd")), "변화도 사용량도 없는 프로세스는 후보 아님");
    }

    @Test
    void 원래_있던_프로세스가_크게_늘면_급증() {
        List<ProcessHistory> rows = new ArrayList<>();
        long start = T0.minusSeconds(3600).toEpochMilli();
        for (int m = 0; m < 5; m++) {
            rows.add(new ProcessHistory(1L, min(m), "mysqld", 10L, start, m < 2 ? 5 : 60, 10));
        }
        List<ProcessTrend.Suspect> s = ProcessTrend.analyze(rows, 5);
        assertEquals("RISING", s.get(0).type());
        assertEquals(5.0, s.get(0).startCpu());
        assertEquals(60.0, s.get(0).peakCpu());
    }
}
