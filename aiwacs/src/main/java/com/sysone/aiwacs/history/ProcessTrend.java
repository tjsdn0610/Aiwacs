package com.sysone.aiwacs.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 프로세스 이력으로 "어떤 프로세스가 언제부터 어떻게 변했는지"를 코드가 계산한다 (판정·집계는 코드, 해석은 AI).
 * AI에게는 이 계산 결과만 넘겨서, AI가 숫자를 지어내거나 잘못 읽을 여지를 줄인다.
 *
 * 구분:
 * - NEW    새로 등장: 구간 시작에는 없던 프로세스가 중간에 나타나 CPU/메모리를 씀 (가장 강한 원인 후보)
 * - RISING 급증: 원래 있던 프로세스의 CPU가 20%p 이상 또는 메모리가 10%p 이상 늘어남
 * - STEADY 원래 높음: 구간 내내 비슷하게 높음 (이번 변화의 원인일 가능성은 낮음)
 */
public final class ProcessTrend {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /** 같은 이름의 프로세스 여러 개(예: stress-ng 작업자 4개)는 합쳐서 본다 */
    public record Suspect(String name, Set<Long> pids, String type, String firstSeen, String lastSeen,
                          boolean endedInWindow, double startCpu, double peakCpu, double avgCpu, double endCpu,
                          double startMem, double peakMem, double endMem, List<double[]> series) {

        public String typeKr() {
            return switch (type) {
                case "NEW" -> "새로 등장";
                case "RISING" -> "급증";
                default -> "원래 높음";
            };
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("pids", pids);
            m.put("type", type);
            m.put("typeKr", typeKr());
            m.put("firstSeen", firstSeen);
            m.put("lastSeen", lastSeen);
            m.put("endedInWindow", endedInWindow);
            m.put("startCpu", startCpu);
            m.put("peakCpu", peakCpu);
            m.put("avgCpu", avgCpu);
            m.put("endCpu", endCpu);
            m.put("startMem", startMem);
            m.put("peakMem", peakMem);
            m.put("endMem", endMem);
            m.put("series", series); // [epoch ms, cpu, mem] — 화면의 작은 그래프용
            return m;
        }
    }

    private ProcessTrend() {
    }

    public static List<Suspect> analyze(List<ProcessHistory> rows, int limit) {
        TreeSet<Instant> minutes = new TreeSet<>();
        Map<String, TreeMap<Instant, double[]>> byName = new LinkedHashMap<>(); // name → 분 → [cpu합, mem합]
        Map<String, Set<Long>> pids = new LinkedHashMap<>();
        Map<String, Long> oldestStart = new LinkedHashMap<>(); // 같은 이름 중 가장 먼저 시작한 시각
        Set<String> startUnknown = new LinkedHashSet<>();
        for (ProcessHistory r : rows) {
            minutes.add(r.getTime());
            double[] v = byName.computeIfAbsent(r.getName(), k -> new TreeMap<>())
                    .computeIfAbsent(r.getTime(), k -> new double[2]);
            v[0] += r.getCpu();
            v[1] += r.getMem();
            if (r.getPid() != null) {
                pids.computeIfAbsent(r.getName(), k -> new LinkedHashSet<>()).add(r.getPid());
            }
            if (r.getStarted() == null) {
                startUnknown.add(r.getName());
            } else {
                oldestStart.merge(r.getName(), r.getStarted(), Math::min);
            }
        }
        if (minutes.isEmpty()) {
            return List.of();
        }
        Instant first = minutes.first();
        Instant last = minutes.last();

        List<Suspect> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Instant, double[]>> e : byName.entrySet()) {
            TreeMap<Instant, double[]> t = e.getValue();
            double[] start = t.getOrDefault(first, new double[2]);
            double[] end = t.getOrDefault(last, new double[2]);
            double peakCpu = 0, peakMem = 0, sumCpu = 0;
            List<double[]> series = new ArrayList<>();
            for (Instant m : minutes) {
                double[] v = t.getOrDefault(m, new double[2]);
                peakCpu = Math.max(peakCpu, v[0]);
                peakMem = Math.max(peakMem, v[1]);
                sumCpu += v[0];
                series.add(new double[] {m.toEpochMilli(), round1(v[0]), round1(v[1])});
            }
            double avgCpu = sumCpu / minutes.size();
            // 시작 시각을 알면 그걸로(구간 시작 이후에 시작 = 새로 등장), 모르면(예전 Agent) "구간 첫 1분 목록에 없었음"으로 추정
            boolean newlyStarted = startUnknown.contains(e.getKey()) || !oldestStart.containsKey(e.getKey())
                    ? !t.containsKey(first) && minutes.size() > 1
                    : oldestStart.get(e.getKey()) >= first.toEpochMilli();

            String type;
            if (newlyStarted && (peakCpu >= 10 || peakMem >= 5)) {
                type = "NEW";
            } else if (peakCpu - start[0] >= 20 || peakMem - start[1] >= 10) {
                type = "RISING";
            } else if (avgCpu >= 20 || peakMem >= 20) {
                type = "STEADY";
            } else {
                continue; // 눈에 띄는 변화도, 높은 사용량도 없음 → 후보 아님
            }
            out.add(new Suspect(e.getKey(), pids.getOrDefault(e.getKey(), Set.of()), type,
                    HM.format(t.firstKey()), HM.format(t.lastKey()), t.lastKey().isBefore(last),
                    round1(start[0]), round1(peakCpu), round1(avgCpu), round1(end[0]),
                    round1(start[1]), round1(peakMem), round1(end[1]), series));
        }
        // 새로 등장 > 급증 > 원래 높음, 같은 구분 안에서는 최고 CPU 순
        List<String> order = List.of("NEW", "RISING", "STEADY");
        out.sort(Comparator.comparingInt((Suspect s) -> order.indexOf(s.type()))
                .thenComparing(Comparator.comparingDouble(Suspect::peakCpu).reversed()));
        return out.stream().limit(limit).toList();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
