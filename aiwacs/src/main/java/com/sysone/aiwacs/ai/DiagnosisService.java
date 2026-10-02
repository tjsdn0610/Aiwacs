package com.sysone.aiwacs.ai;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.sysone.aiwacs.action.ActionService;
import com.sysone.aiwacs.alarm.Alarm;
import com.sysone.aiwacs.alarm.AlarmService;
import com.sysone.aiwacs.history.MetricHistory;
import com.sysone.aiwacs.history.MetricHistoryService;
import com.sysone.aiwacs.history.ProcessHistoryService;
import com.sysone.aiwacs.history.ProcessTrend;
import com.sysone.aiwacs.server.AgentReport;
import com.sysone.aiwacs.server.MonitoredServer;
import com.sysone.aiwacs.server.ServerService;

import tools.jackson.databind.JsonNode;

/**
 * AI 상태 진단 → 조치 제안 → 처리 기록 초안.
 *
 * 역할 나눔 (설계 원칙):
 * - 코드: 판정(정상~장애), 최근 N분 동안 어떤 프로세스가 새로 생겼는지/늘었는지 계산, 조치 가능 여부(보호 프로세스·Agent 허용) 결정
 * - AI : 그 계산 결과를 사람 말로 해석하고, 조치 후보 중 하나를 "추천"만 한다
 * - 사람: 추천을 보고 [실행]/[그대로 두기]를 고른다. AI는 조치 API를 부르지 않는다
 */
@Service
public class DiagnosisService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    /** 진단 구간 최대 (지표 이력은 7일 보관이지만, 진단은 사건 하나를 보는 용도라 길 필요가 없음) */
    private static final int MAX_MINUTES = 180;
    /** 처리 기록 초안에 "직전 진단"을 쓸 수 있는 시간 */
    private static final Duration DIAGNOSIS_FRESH = Duration.ofHours(3);

    private final AiClient ai;
    private final ServerService servers;
    private final MetricHistoryService metricHistory;
    private final ProcessHistoryService processHistory;
    private final ActionService actions;
    private final AlarmService alarms;

    /** 서버별 마지막 진단 결과 (처리 기록 초안의 근거로 재사용) */
    private record Last(Instant at, Map<String, Object> result) {}
    private final Map<Long, Last> lastDiagnosis = new ConcurrentHashMap<>();

    public DiagnosisService(AiClient ai, ServerService servers, MetricHistoryService metricHistory,
                            ProcessHistoryService processHistory, ActionService actions, AlarmService alarms) {
        this.ai = ai;
        this.servers = servers;
        this.metricHistory = metricHistory;
        this.processHistory = processHistory;
        this.actions = actions;
        this.alarms = alarms;
    }

    // ===================== 1) 상태 진단 + 조치 제안 =====================

    /**
     * @param minutes 0이면 "지금 이 순간"만, 1 이상이면 최근 N분 이력까지 보고 원인 프로세스를 찾는다
     *                (부하가 이미 끝난 뒤에 눌러도 그 시간에 무슨 일이 있었는지 알 수 있게)
     */
    public Map<String, Object> diagnose(Long serverId, int minutes) {
        if (!ai.isConfigured()) {
            return Map.of("ok", false, "reply", AiService.NOT_CONFIGURED);
        }
        MonitoredServer server = serverId == null ? null : servers.findById(serverId).orElse(null);
        if (server == null) {
            return Map.of("ok", false, "reply", "진단할 서버를 선택해 주세요.");
        }
        if (!servers.isOnline(serverId)) {
            return Map.of("ok", false, "reply",
                    "'" + server.label() + "' 서버가 오프라인 상태라 진단할 수 없습니다. Agent 실행 여부를 확인해 주세요.");
        }
        minutes = Math.max(0, Math.min(MAX_MINUTES, minutes));
        AgentReport report = servers.latest(serverId).orElseThrow().report();
        Map<String, Map<String, Object>> status = servers.judge(serverId).orElseThrow();
        List<AgentReport.Proc> procs = report.procs() == null ? List.of() : report.procs();

        // ----- 코드가 계산: 최근 N분 서버 추이 + 프로세스 변화 -----
        Instant from = Instant.now().minus(Duration.ofMinutes(minutes)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        List<MetricHistory> trend = minutes > 0 ? metricHistory.since(serverId, from) : List.of();
        List<ProcessTrend.Suspect> suspects = minutes > 0
                ? ProcessTrend.analyze(processHistory.since(serverId, from), 5) : List.of();

        // ----- 조치 후보: 원인 후보 중 지금도 실행 중인 것 + 지금 CPU 상위 3개 (같은 이름은 CPU 가장 높은 PID) -----
        Map<String, AgentReport.Proc> runningByName = new LinkedHashMap<>();
        procs.stream().sorted(Comparator.comparingDouble(AgentReport.Proc::cpu).reversed())
                .forEach(p -> runningByName.putIfAbsent(p.name(), p));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ProcessTrend.Suspect s : suspects) {
            addCandidate(candidates, serverId, runningByName.get(s.name()), s.name(), s.typeKr());
        }
        procs.stream().sorted(Comparator.comparingDouble(AgentReport.Proc::cpu).reversed()).limit(3)
                .forEach(p -> addCandidate(candidates, serverId, p, p.name(), "현재 CPU 상위"));

        List<Map<String, Object>> suspectMaps = new ArrayList<>();
        for (ProcessTrend.Suspect s : suspects) {
            Map<String, Object> m = s.toMap();
            m.put("running", runningByName.containsKey(s.name()));
            suspectMaps.add(m);
        }

        // ----- AI 해석 -----
        String prompt = prompt(server, status, report, minutes, trend, suspects, runningByName, candidates);
        JsonNode result;
        try {
            result = ai.extractJson(ai.generate(prompt), false);
        } catch (AiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AiService.AI_BUSY);
        } catch (AiClient.AiUnavailableException e) {
            return Map.of("ok", false, "reply", AiService.AI_UNAVAILABLE);
        } catch (Exception e) {
            return Map.of("ok", false, "reply", "진단 실패: " + AiService.shortError(e));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("serverId", serverId);
        resp.put("server", server.label());
        resp.put("minutes", minutes);
        resp.put("status", status);
        resp.put("diagnosis", result);
        resp.put("trend", trend.stream().map(h -> List.of(h.getTime().toEpochMilli(), h.getCpu(), h.getMemory())).toList());
        resp.put("suspects", suspectMaps);
        resp.put("candidates", candidates);
        resp.put("recommendation", recommendation(result.path("recommend"), candidates));
        resp.put("simulation", actions.isSimulation());
        resp.put("agentActionEnabled", Boolean.TRUE.equals(report.actionEnabled()));
        lastDiagnosis.put(serverId, new Last(Instant.now(), resp));
        return resp;
    }

    private void addCandidate(List<Map<String, Object>> out, Long serverId, AgentReport.Proc p, String name, String why) {
        if (out.stream().anyMatch(c -> name.equals(c.get("name")))) {
            return;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("why", why);
        c.put("running", p != null);
        if (p != null) {
            c.put("pid", p.pid());
            c.put("start", p.start());
            c.put("user", p.user());
            c.put("cpu", p.cpu());
            c.put("mem", p.mem());
            c.put("blocked", actions.blockedReason(serverId, p)); // null이면 조치 버튼 표시
        }
        out.add(c);
    }

    /** AI 추천을 코드가 검증: 후보 목록에 있는 프로세스 + 허용된 조치만 통과. 아니면 '그대로 두기' */
    private static Map<String, Object> recommendation(JsonNode rec, List<Map<String, Object>> candidates) {
        String name = rec.path("process").asString("").strip();
        String action = rec.path("action").asString("none").strip();
        String reason = rec.path("reason").asString("").strip();
        Optional<Map<String, Object>> target = candidates.stream().filter(c -> name.equals(c.get("name"))).findFirst();

        Map<String, Object> m = new LinkedHashMap<>();
        if (target.isEmpty() || !ActionService.ACTIONS.contains(action)) {
            m.put("action", "none");
            m.put("process", target.map(t -> t.get("name")).orElse(null));
        } else if (!Boolean.TRUE.equals(target.get().get("running"))) {
            m.put("action", "none");
            m.put("process", name);
            reason = name + "은(는) 이미 종료되어 조치가 필요 없습니다. " + reason;
        } else {
            m.put("action", action);
            m.put("process", name);
        }
        m.put("reason", reason.isBlank() ? "AI가 별도 조치를 추천하지 않았습니다." : reason);
        return m;
    }

    private String prompt(MonitoredServer server, Map<String, Map<String, Object>> status, AgentReport report,
                          int minutes, List<MetricHistory> trend, List<ProcessTrend.Suspect> suspects,
                          Map<String, AgentReport.Proc> running, List<Map<String, Object>> candidates) {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", server.label());
        serverInfo.put("os", server.getOs());
        serverInfo.put("company", server.getCompany());
        serverInfo.put("policy", servers.summary(server).get("policyName"));

        Map<String, Object> detail = new LinkedHashMap<>();
        if (report.detail() != null) detail.putAll(report.detail());
        if (report.io() != null) detail.putAll(report.io());
        List<Map<String, Object>> topNow = running.values().stream().limit(6).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("cpu", p.cpu());
            m.put("mem", p.mem());
            m.put("user", p.user());
            return m;
        }).toList();
        List<String> trendLines = trend.stream()
                .map(h -> HM.format(h.getTime()) + " CPU " + h.getCpu() + "% / MEM " + h.getMemory() + "%").toList();
        List<Map<String, Object>> suspectFacts = suspects.stream().map(s -> {
            Map<String, Object> m = s.toMap();
            m.remove("series");
            m.put("runningNow", running.containsKey(s.name()));
            return m;
        }).toList();
        List<String> candidateNames = candidates.stream()
                .filter(c -> Boolean.TRUE.equals(c.get("running")) && c.get("blocked") == null)
                .map(c -> String.valueOf(c.get("name"))).toList();

        return """
                당신은 20년 경력의 시스템 성능 분석 전문가입니다. 아래 사실만 근거로 분석하고, 반드시 규칙을 지키세요.

                [분석 규칙]
                - 상태 판정(정상/주의/경고/위험/장애)은 이미 시스템이 내렸습니다. 바꾸지 말고 해석만 하세요.
                - 지표들 사이의 인과관계를 화살표(→)로 설명하세요. 예: 새 프로세스 등장 → CPU 100%% → 처리 대기(Load) 증가
                - [프로세스 변화]는 코드가 이력으로 계산한 결과입니다. type이 NEW(새로 등장)·RISING(급증)인 프로세스가
                  가장 유력한 원인 후보이고, STEADY(원래 높음)는 이번 변화의 원인일 가능성이 낮습니다.
                  firstSeen~lastSeen(시각)과 peakCpu 같은 수치를 그대로 인용하세요. 숫자를 지어내지 마세요.
                - runningNow=false면 그 프로세스는 이미 끝났습니다. 이 경우 "지금은 조치가 필요 없고 기록만 남기면 된다"고 말하세요.
                - 원인을 단정하지 마세요. "~일 가능성이 있습니다", "~로 보입니다" 형태로만.
                - IT 비전문가도 이해하도록 쉬운 말로 (전문용어는 괄호로 풀어서).

                [조치 추천 규칙] 추천만 합니다. 실행 여부는 사람이 정합니다.
                - process는 [조치 가능 후보]에 있는 이름 중 하나만, 없으면 빈 문자열 (목록 밖의 이름 금지)
                - action:
                  "terminate" = 정상 종료. 테스트·부하 도구(stress, stress-ng, yes, dd 등)나 폭주한 임시 작업처럼 꺼도 되는 것
                  "renice"    = 우선순위 낮추기. 끄면 안 되는 업무 프로세스(DB, 웹서버, java, 백업, 배치)가 CPU를 많이 쓸 때
                  "none"      = 그대로 두기. 정상 범위이거나, 원인 프로세스가 이미 끝났거나, 근거가 약할 때
                - reason에는 왜 그 조치인지와, 실행 전에 사람이 확인할 점을 한두 문장으로

                [출력 형식] JSON만. 다른 말 금지.
                {"level": "정상|주의|경고|위험|장애", "summary": "지금 무슨 일인지 1~2문장", "timeline": "최근 구간에 언제부터 언제까지 무슨 일이 있었는지 1~2문장 (구간 정보가 없으면 빈 문자열)", "correlation": "인과관계(→)와 쉬운 설명", "causes": ["가능성 있는 원인1", "원인2"], "check": "직접 확인해볼 방법", "action": "권장 조치를 단계별로", "recommend": {"process": "후보 이름 또는 빈 문자열", "action": "terminate|renice|none", "reason": "추천 이유와 확인할 점"}}

                [서버 정보]
                %s

                [현재 상태 및 판정]
                %s

                [세부 지표] (_s로 끝나는 값은 초당 값. major_faults_s가 높으면 메모리 부족 신호, disk_busy_percent가 높으면 디스크 병목 가능성)
                %s

                [지금 실행 중인 상위 프로세스]
                %s

                [최근 %d분 서버 추이] (1분 평균, 오래된 순)
                %s

                [프로세스 변화] (코드가 최근 %d분 이력으로 계산)
                %s

                [조치 가능 후보] (지금 실행 중이고 보호 대상이 아닌 프로세스)
                %s""".formatted(ai.toJson(serverInfo), ai.toJson(status), ai.toJson(detail), ai.toJson(topNow),
                minutes, minutes == 0 ? "(지금 이 순간만 진단)" : String.join("\n", trendLines),
                minutes, ai.toJson(suspectFacts), ai.toJson(candidateNames));
    }

    // ===================== 2) 처리 기록 초안 =====================

    /**
     * 선택한 알람으로 처리 기록 초안을 만든다. 사람이 고쳐서 저장한다 (AI가 직접 저장하지 않음).
     * 근거: 알람 경과(발생·해제·횟수) + 그 서버의 직전 진단 + 그 사이 실행한 조치 이력.
     * AI가 실패해도 코드가 같은 사실로 기본 초안을 만든다.
     */
    public Map<String, Object> handleDraft(List<Long> alarmIds, String processStatus) {
        List<Alarm> list = alarms.byIds(alarmIds);
        if (list.isEmpty()) {
            return Map.of("ok", false, "reply", "초안을 쓸 알림을 선택해 주세요.");
        }
        String statusKr;
        try {
            statusKr = Alarm.ProcessStatus.valueOf(processStatus).kr;
        } catch (Exception e) {
            statusKr = Alarm.ProcessStatus.COMPLETE.kr;
        }

        Instant first = list.stream().map(Alarm::getFirstAt).min(Comparator.naturalOrder()).orElseThrow();
        boolean allResolved = list.stream().allMatch(a -> a.getStatus() == Alarm.Status.RESOLVED);
        Instant resolved = allResolved
                ? list.stream().map(Alarm::getResolvedAt).max(Comparator.naturalOrder()).orElse(null) : null;
        List<Long> serverIds = list.stream().map(Alarm::getServerId).distinct().toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("servers", list.stream().map(Alarm::getServer).distinct().toList());
        facts.put("alarms", list.stream().map(a -> a.getLevel() + " · " + a.title() + " · 발생 " + a.getCount() + "회 · "
                + (a.getStatus() == Alarm.Status.RESOLVED ? "해제 " + Alarm.fmt(a.getResolvedAt()) : "발생 중")).toList());
        facts.put("firstAt", Alarm.fmt(first));
        facts.put("resolvedAt", resolved == null ? "아직 발생 중" : Alarm.fmt(resolved));
        facts.put("durationMin", Duration.between(first, resolved != null ? resolved : Instant.now()).toMinutes());

        List<Map<String, Object>> diag = new ArrayList<>();
        List<Map<String, Object>> acts = new ArrayList<>();
        for (Long sid : serverIds) {
            Last last = lastDiagnosis.get(sid);
            if (last != null && last.at().isAfter(Instant.now().minus(DIAGNOSIS_FRESH))) {
                JsonNode d = (JsonNode) last.result().get("diagnosis");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("server", last.result().get("server"));
                m.put("diagnosedAt", Alarm.fmt(last.at()));
                m.put("summary", d.path("summary").asString(""));
                m.put("timeline", d.path("timeline").asString(""));
                m.put("causes", d.path("causes"));
                diag.add(m);
            }
            acts.addAll(actions.since(sid, first.minus(Duration.ofMinutes(10))));
        }
        facts.put("diagnosis", diag);
        facts.put("actions", acts.stream().map(a -> a.get("createdAt") + " " + a.get("by") + "이(가) " + a.get("name")
                + "(PID " + a.get("pid") + ") " + a.get("actionKr") + " 승인 → " + a.get("status") + ": " + a.get("message")).toList());

        String fallback = fallbackDraft(statusKr, facts);
        if (!ai.isConfigured()) {
            return Map.of("ok", true, "draft", fallback, "source", "code");
        }
        String prompt = """
                당신은 서버 관제 담당자의 처리 기록 작성을 돕습니다. 아래 사실만으로 처리 기록 초안을 쓰세요.
                - 처리 상태는 "%s"입니다. 상태에 맞게 쓰세요 (점검 중: 지금 무엇을 하고 있는지 / 완료: 원인·조치·결과 / 보류·무시: 그 판단 이유).
                - 2문장 이내, 150자 이내로 짧게. 한국어 기록체(~함, ~임). 알림을 하나씩 나열하지 말고 "CPU 알림 12건"처럼 요약하세요.
                - 시각과 수치는 사실에 있는 것만 쓰고 지어내지 마세요.
                - 원인은 진단 결과가 있을 때만 "~로 추정"으로 쓰고, 없으면 "원인 확인 필요"라고 쓰세요.
                - 조치 이력이 있으면 누가 무엇을 실행했고 결과가 어땠는지 포함하세요.
                - JSON만: {"draft": "초안 문장"}

                [사실]
                %s""".formatted(statusKr, ai.toJson(facts));
        try {
            String draft = ai.extractJson(ai.generate(prompt), false).path("draft").asString("").strip();
            return draft.isBlank()
                    ? Map.of("ok", true, "draft", fallback, "source", "code")
                    : Map.of("ok", true, "draft", draft, "source", "ai");
        } catch (Exception e) {
            return Map.of("ok", true, "draft", fallback, "source", "code",
                    "aiNote", "AI 초안을 받지 못해 기본 초안을 넣었습니다.");
        }
    }

    /** AI 없이 사실만으로 만든 기본 초안 */
    @SuppressWarnings("unchecked")
    private static String fallbackDraft(String statusKr, Map<String, Object> facts) {
        StringBuilder sb = new StringBuilder("[" + statusKr + "] ");
        sb.append(String.join(", ", (List<String>) facts.get("servers")))
                .append(" 알림 ").append(((List<?>) facts.get("alarms")).size()).append("건, ")
                .append(facts.get("firstAt")).append(" 발생 → ").append(facts.get("resolvedAt"))
                .append(" (").append(facts.get("durationMin")).append("분). ");
        List<Map<String, Object>> diag = (List<Map<String, Object>>) facts.get("diagnosis");
        sb.append(diag.isEmpty() ? "원인 확인 필요. " : "원인 추정: " + diag.get(0).get("summary") + " ");
        List<String> acts = (List<String>) facts.get("actions");
        if (!acts.isEmpty()) {
            sb.append("조치: ").append(acts.get(0)).append('.');
        }
        return sb.toString().strip();
    }
}
