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
    /** 진단 구간 최대 (지표 이력은 7일 보관이지만, 진단은 이벤트 하나를 보는 용도라 길 필요가 없음) */
    private static final int MAX_MINUTES = 180;
    /** 처리 기록 초안에 "직전 진단"을 쓸 수 있는 시간 */
    private static final Duration DIAGNOSIS_FRESH = Duration.ofHours(3);

    private final AiClient ai;
    private final ServerService servers;
    private final MetricHistoryService metricHistory;
    private final ProcessHistoryService processHistory;
    private final ActionService actions;
    private final AlarmService alarms;
    private final WiderContext wider;

    /** 서버별 마지막 진단 결과 (처리 기록 초안의 근거로 재사용) */
    private record Last(Instant at, Map<String, Object> result) {}
    private final Map<Long, Last> lastDiagnosis = new ConcurrentHashMap<>();

    public DiagnosisService(AiClient ai, ServerService servers, MetricHistoryService metricHistory,
                            ProcessHistoryService processHistory, ActionService actions, AlarmService alarms,
                            WiderContext wider) {
        this.ai = ai;
        this.servers = servers;
        this.metricHistory = metricHistory;
        this.processHistory = processHistory;
        this.actions = actions;
        this.alarms = alarms;
        this.wider = wider;
    }

    // ===================== 1) 상태 진단 + 조치 제안 =====================

    /** 이벤트에서 들어왔을 때: 이벤트 시작보다 이만큼 앞부터 본다 (부하 '전' 모습이 구간에 들어가야 "새로 등장"을 판단할 수 있음) */
    private static final Duration BEFORE_EVENT = Duration.ofMinutes(5);

    /**
     * 이벤트 기준 진단: 구간을 "이벤트 시작 5분 전 ~ 지금"으로 자동으로 맞춘다.
     * 운영자가 몇 분을 볼지 고민할 필요 없이, 항상 '부하 전 → 시작 → 지금'이 구간에 들어간다.
     * (이벤트가 MAX_MINUTES보다 오래됐으면 최근 MAX_MINUTES분까지만)
     */
    public Map<String, Object> diagnoseSince(Long serverId, Instant eventStart) {
        return diagnoseSince(serverId, eventStart, false);
    }

    public Map<String, Object> diagnoseSince(Long serverId, Instant eventStart, boolean extended) {
        long minutes = (long) Math.ceil(Duration.between(eventStart.minus(BEFORE_EVENT), Instant.now()).toSeconds() / 60.0);
        Map<String, Object> resp = diagnose(serverId, (int) Math.max(1, minutes), extended);
        if (Boolean.TRUE.equals(resp.get("ok"))) {
            resp.put("eventStart", Alarm.fmt(eventStart));
        }
        return resp;
    }

    /**
     * @param minutes 0이면 "지금 이 순간"만, 1 이상이면 최근 N분 이력까지 보고 원인 프로세스를 찾는다
     *                (부하가 이미 끝난 뒤에 눌러도 그 시간에 무슨 일이 있었는지 알 수 있게)
     */
    public Map<String, Object> diagnose(Long serverId, int minutes) {
        return diagnose(serverId, minutes, false);
    }

    /**
     * @param extended true = 2차 진단: 사람이 [과거 데이터까지 분석]을 고른 경우. 같은 시간대 다른 서버 이벤트,
     *                 평소 대비(지난 7일), 과거 비슷한 이벤트의 처리 기록까지 코드가 모아 AI에게 넘긴다.
     */
    public Map<String, Object> diagnose(Long serverId, int minutes, boolean extended) {
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
        Map<String, Object> ctx = extended && minutes > 0 ? wider.build(serverId, server.label(), from, suspects) : null;
        String prompt = (ctx == null ? "" : widerHead(ctx))
                + prompt(server, status, report, minutes, trend, suspects, runningByName, candidates, ctx != null)
                + (ctx == null ? "" : widerPrompt(ctx));
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
        resp.put("windowFrom", minutes > 0 ? Alarm.fmt(from) : null);
        resp.put("status", status);
        resp.put("diagnosis", result);
        resp.put("trend", trend.stream().map(h -> List.of(h.getTime().toEpochMilli(), h.getCpu(), h.getMemory())).toList());
        resp.put("suspects", suspectMaps);
        resp.put("candidates", candidates);
        resp.put("recommendation", recommendation(recommendNode(result), candidates));
        resp.put("simulation", actions.isSimulation());
        resp.put("agentActionEnabled", Boolean.TRUE.equals(report.actionEnabled()));
        resp.put("extended", ctx != null);
        if (ctx != null) {
            resp.put("context", ctx);
            Map<String, Object> root = rootOf(result, ctx);
            if (root != null) {
                resp.put("root", root);
                // 근본 원인이 다른 서버에 있으면 이 서버에서는 조치하지 않는 것이 맞다 (증상만 누르는 셈)
                @SuppressWarnings("unchecked")
                Map<String, Object> rec = (Map<String, Object>) resp.get("recommendation");
                rec.put("action", "none");
                rec.put("process", null);
                rec.put("reason", "근본 원인이 " + root.get("server") + "에 있을 가능성이 커, 이 서버에서는 조치하지 않고 원인 서버를 먼저 진단합니다.");
            }
        } else if (minutes > 0) {
            // 1차 진단의 근거가 약하면 사람에게 "과거 데이터까지 분석할까요?"를 묻는다 (판단은 정해진 규칙으로)
            boolean hasNew = suspects.stream().anyMatch(x -> "NEW".equals(x.type()));
            boolean noAction = "none".equals(((Map<?, ?>) resp.get("recommendation")).get("action"));
            if (!hasNew || noAction) {
                resp.put("widenSuggested", true);
                resp.put("widenReason", !hasNew
                        ? "이 서버의 이벤트 구간에서 새로 등장한 프로세스가 없어, 지금 데이터만으로는 원인이 분명하지 않습니다."
                        : "AI가 이 서버에서 조치할 대상을 정하지 못했습니다.");
            }
        }
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

    /**
     * AI의 조치 추천 위치. 작은 모델이 이름이 비슷한 "action"(권장 조치) 칸에 추천 객체를
     * (객체 또는 JSON 글자로) 넣고 "recommend"를 비우는 경우가 있어, 그때는 그 칸에서 꺼낸다.
     * 꺼낸 값도 아래 recommendation()에서 코드가 똑같이 검증한다.
     */
    private JsonNode recommendNode(JsonNode result) {
        JsonNode rec = result.path("recommend");
        if (rec.isObject() && !rec.path("process").asString("").isBlank()) {
            return rec;
        }
        JsonNode act = result.path("action");
        if (act.isObject() && act.has("process")) {
            return act;
        }
        String text = act.asString("").strip();
        if (text.startsWith("{") && text.contains("\"process\"")) {
            try {
                JsonNode parsed = ai.extractJson(text, false);
                if (parsed.has("process")) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // 글자가 JSON이 아니면 원래대로 (추천 없음)
            }
        }
        return rec;
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

    /** 2차 진단 맨 앞에 두는 지시: 시스템이 계산한 근본 원인 후보를 먼저 보여 주고 판단하게 한다 (작은 모델이 뒤쪽 근거를 놓치지 않게) */
    @SuppressWarnings("unchecked")
    private String widerHead(Map<String, Object> ctx) {
        List<Map<String, Object>> cands = (List<Map<String, Object>>) ctx.get("rootCandidates");
        if (cands == null || cands.isEmpty()) {
            return "[2차 진단] 사람이 '과거 데이터까지 분석'을 요청했습니다. 맨 아래 [넓혀 본 근거]를 반드시 함께 보고 해석하세요.\n\n";
        }
        Map<String, Object> c = cands.get(0);
        return """
                [2차 진단 — 가장 먼저 읽으세요]
                사람이 '과거 데이터까지 분석'을 요청했습니다. 이 서버 안에서는 새로 등장한 프로세스가 없어, 시스템이 다른 서버·평소 대비·과거 기록까지 넓혀 근본 원인 후보를 찾았습니다.
                시스템이 찾은 근본 원인 후보: %s의 %s
                근거: %s
                → 이 서버의 CPU·프로세스 변화가 이 후보의 결과(증상)인지 판단하고, correlation에 그 연결을 서버 이름과 함께 화살표(→)로 쓰세요.
                  예) "DB서버 pg_dump 시작 → DB 응답 지연 → 웹서버 php-fpm 요청 적체 → CPU 100%%" (이 서버의 프로세스는 '새로 등장'이 아니라 '늘어남'입니다)
                → 근거가 맞다면 이 서버의 프로세스(평소에도 도는 업무 프로세스)는 원인이 아니라 증상이므로, 이 서버의 recommend.action은 "none"입니다.
                → action(권장 조치)에는 이 서버의 프로세스를 끄라는 내용을 쓰지 말고, 원인 서버에서 확인·조치할 일을 단계별로 쓰세요.
                → rootCause에는 근본 원인을 한 문장으로(가능성으로), pastNote에는 과거 기록·평소 대비에서 참고한 점을 한 문장으로 쓰세요.

                """.formatted(c.get("server"), c.get("process"), String.join(" / ", (List<String>) c.get("reasons")));
    }

    /** 2차 진단에 덧붙이는 근거와 규칙 (근거는 모두 코드가 계산한 사실) */
    private String widerPrompt(Map<String, Object> ctx) {
        return """


                [넓혀 본 근거] 사람이 '과거 데이터까지 분석'을 요청했습니다. 아래는 시스템이 계산한 사실입니다.
                - 이 서버의 이벤트 시작: %s
                - 같은 시간대 다른 서버 이벤트 (시작 순, 그 서버에서 새로 등장한 프로세스 포함):
                %s
                - 평소 대비 (지금 구간 vs 지난 7일 같은 시간대 평균):
                %s
                - 원인 후보 프로세스가 평소 언제 돌았는지:
                %s
                - 과거 비슷한 이벤트의 처리 기록 (엔지니어가 남긴 기록):
                %s

                [넓혀 본 근거 해석 규칙]
                - 이 서버의 변화가 다른 서버 이벤트의 결과(증상)일 가능성을 먼저 검토하세요.
                  다른 서버 이벤트가 먼저 시작했고 그 서버에 평소 없던 프로세스가 새로 등장했다면, 그것이 근본 원인일 가능성이 높습니다.
                - 평소에도 이 시간에 늘 돌던 업무 프로세스가 늘어난 것은 원인보다 증상일 가능성이 큽니다.
                - 평소와 다른 시간에 돈 작업(예: 평소 새벽에 돌던 작업이 지금 실행)은 강한 근거입니다.
                - 과거 처리 기록에 같은 상황의 원인·해결 방법이 있으면 날짜와 함께 인용하세요. 기록에 없는 내용은 지어내지 마세요.
                - 과거 처리 기록에 같은 상황에서 효과가 있었던 조치가 있으면, 그 조치를 우선 고려하세요.
                  백업처럼 다시 실행할 수 있는 작업이 업무 시간에 돌아 다른 서버까지 영향을 주면, "terminate"(정상 종료 후 한가한 시간에 재실행)를 추천할 수 있습니다.
                - 근본 원인이 다른 서버에 있으면 rootServer에 그 서버 이름을 그대로 쓰고, 이 서버의 recommend.action은 "none"으로 하세요.
                - 출력 JSON에 다음 항목을 추가하세요:
                  "rootServer": "근본 원인으로 보이는 다른 서버 이름 (이 서버가 원인이면 빈 문자열)",
                  "rootCause": "근본 원인 한 문장 (가능성으로)",
                  "pastNote": "과거 기록·평소 대비에서 참고한 점 한 문장 (없으면 빈 문자열)"
                """.formatted(ctx.get("thisStartedAt"), ai.toJson(ctx.get("otherEvents")), ai.toJson(ctx.get("baseline")),
                ai.toJson(ctx.get("processUsual")), ai.toJson(ctx.get("pastIncidents")));
    }

    /** AI가 말한 근본 원인 서버를 코드가 검증: 같은 시간대 이벤트 목록에 있는 서버만 인정 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> rootOf(JsonNode result, Map<String, Object> ctx) {
        // 근본 원인 후보는 시스템이 정해진 규칙으로 고른 것을 쓴다 (원인 후보 프로세스처럼 '계산은 시스템') — AI는 그 후보를 해석
        List<Map<String, Object>> cands = (List<Map<String, Object>>) ctx.get("rootCandidates");
        if (cands != null && !cands.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>(cands.get(0));
            m.put("cause", result.path("rootCause").asString(""));
            return m;
        }
        String name = result.path("rootServer").asString("").strip();
        if (name.isEmpty()) {
            return null;
        }
        for (Map<String, Object> o : (List<Map<String, Object>>) ctx.get("otherEvents")) {
            String srv = String.valueOf(o.get("server"));
            if (srv.equals(name) || name.contains(srv) || srv.contains(name)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("serverId", o.get("serverId"));
                m.put("server", srv);
                m.put("since", o.get("since"));
                m.put("cause", result.path("rootCause").asString(""));
                return m;
            }
        }
        return null;
    }

    private String prompt(MonitoredServer server, Map<String, Map<String, Object>> status, AgentReport report,
                          int minutes, List<MetricHistory> trend, List<ProcessTrend.Suspect> suspects,
                          Map<String, AgentReport.Proc> running, List<Map<String, Object>> candidates, boolean extended) {
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
                {"level": "정상|주의|경고|위험|장애", "summary": "지금 무슨 일인지 1~2문장",%s "timeline": "최근 구간에 언제부터 언제까지 무슨 일이 있었는지 1~2문장 (구간 정보가 없으면 빈 문자열)", "correlation": "인과관계(→)와 쉬운 설명", "causes": ["가능성 있는 원인1", "원인2"], "check": "직접 확인해볼 방법", "action": "권장 조치를 단계별로", "recommend": {"process": "후보 이름 또는 빈 문자열", "action": "terminate|renice|none", "reason": "추천 이유와 확인할 점"}}

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
                %s""".formatted(extended ? " \"rootCause\": \"근본 원인 한 문장 (가능성으로)\", \"pastNote\": \"과거 기록·평소 대비에서 참고한 점 한 문장\"," : "", ai.toJson(serverInfo), ai.toJson(status), ai.toJson(detail), ai.toJson(topNow),
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

        // 이 알림의 서버 + 같은 고객사의 다른 서버.
        // 근본 원인이 다른 서버에 있을 수 있어서다 (예: DB서버 백업 때문에 웹서버 CPU가 오름 → 조치는 DB서버에서 함)
        List<String> companies = serverIds.stream().map(servers::findById).flatMap(Optional::stream)
                .map(MonitoredServer::getCompany).filter(c -> c != null && !c.isBlank()).distinct().toList();
        List<Long> related = servers.findAll().stream()
                .filter(s -> !serverIds.contains(s.getId()) && companies.contains(s.getCompany()))
                .map(MonitoredServer::getId).toList();
        Instant actFrom = first.minus(Duration.ofMinutes(10));
        facts.put("diagnosis", diagnoses(serverIds));
        facts.put("actions", actionLines(serverIds, actFrom));
        facts.put("relatedDiagnosis", diagnoses(related));
        facts.put("relatedActions", actionLines(related, actFrom));

        // 현상·조치 줄은 사실 그대로라 코드가 쓰고, AI는 원인·결과 해석만 쓴다 (건수·시각을 AI가 바꾸지 않게)
        String symptom = symptomLine(list, first, resolved);
        List<String> acts = new ArrayList<>(actionLines(serverIds, actFrom));
        acts.addAll(actionLines(related, actFrom));
        String actionLine = acts.isEmpty() ? "조치 없음" : String.join(" / ", acts);
        // 실제로 실행된 조치(사람 승인 + Agent 실행 완료) — 원인 줄 검증과 결과 줄에 쓴다
        List<Map<String, Object>> done = java.util.stream.Stream.concat(serverIds.stream(), related.stream())
                .flatMap(sid -> actions.since(sid, actFrom).stream())
                .filter(a -> "DONE".equals(a.get("status")) || "SIMULATED".equals(a.get("status"))).toList();
        String[] fb = fallbackCauseResult(facts, done);
        if (!ai.isConfigured()) {
            return Map.of("ok", true, "draft", draft(statusKr, symptom, fb[0], actionLine, fb[1]), "source", "code");
        }
        String prompt = """
                당신은 서버 관제 담당자의 처리 기록 작성을 돕습니다. 아래 사실만으로 처리 기록의 "원인" 한 줄을 쓰세요.
                - 처리 상태는 "%s"입니다. 한국어 기록체(~함, ~임), 60자 이내 한 문장.
                - cause: 진단 결과(diagnosis 또는 relatedDiagnosis)가 있으면 "~로 추정됨"으로. 근본 원인이 다른 서버로 진단됐으면(rootCauseServer) 그 서버와 프로세스를 쓰세요. 진단이 없으면 "원인 확인 필요".
                  형식: "<서버>의 <프로세스>(<무엇을 하는 프로세스인지>)가 <자원>을 많이 써 생긴 것으로 추정됨" — 같은 말을 반복하지 마세요.
                  (형식 예: "ABC 서버의 batch-job(정산 작업)이 디스크를 많이 써 생긴 것으로 추정됨")
                - 시각·프로세스 이름은 사실에 있는 것만. 수치를 지어내지 마세요.
                - JSON만: {"cause": "..."}

                [사실]
                %s""".formatted(statusKr, ai.toJson(facts));
        try {
            JsonNode r = ai.extractJson(ai.generate(prompt), false);
            String cause = r.path("cause").asString("").strip();
            // AI가 원인을 다른 프로세스·서버로 잘못 짚을 수 있어(작은 모델), 실제 조치한 프로세스와 서버가 들어 있는지 코드가 확인
            boolean ok = !cause.isBlank() && done.stream().allMatch(a ->
                    cause.contains(String.valueOf(a.get("name"))) && cause.contains(String.valueOf(a.get("server"))));
            // 결과 줄은 해석이 아니라 사실(조치 → 해제 시각)이라 코드가 쓴다
            return Map.of("ok", true, "source", ok ? "ai" : "code",
                    "draft", draft(statusKr, symptom, ok ? cause : fb[0], actionLine, fb[1]));
        } catch (Exception e) {
            return Map.of("ok", true, "draft", draft(statusKr, symptom, fb[0], actionLine, fb[1]), "source", "code",
                    "aiNote", "AI 초안을 받지 못해 기본 초안을 넣었습니다.");
        }
    }

    private static String draft(String statusKr, String symptom, String cause, String action, String result) {
        return "현상: " + symptom + "\n원인: " + cause + "\n조치: " + action + "\n결과: " + result;
    }

    /** 현상 한 줄: 서버 · 자원별 알림 건수(최고 레벨) · 시작 시각 · 지속 시간 */
    private static String symptomLine(List<Alarm> list, Instant first, Instant resolved) {
        List<String> order = List.of("주의", "경고", "위험", "장애");
        Map<String, List<Alarm>> byRes = new LinkedHashMap<>();
        for (Alarm a : list) {
            String t = a.title();
            String res = t.startsWith("CPU") ? "CPU" : t.startsWith("메모리") || t.startsWith("Memory") ? "메모리"
                    : t.startsWith("디스크") || t.startsWith("Disk") ? "디스크" : "기타";
            byRes.computeIfAbsent(res, k -> new ArrayList<>()).add(a);
        }
        List<String> parts = byRes.entrySet().stream().map(e -> e.getKey() + " 알림 " + e.getValue().size() + "건(최고 "
                + e.getValue().stream().map(Alarm::getLevel).max(Comparator.comparingInt(order::indexOf)).orElse("") + ")").toList();
        long min = Duration.between(first, resolved != null ? resolved : Instant.now()).toMinutes();
        return String.join(", ", list.stream().map(Alarm::getServer).distinct().toList()) + " " + String.join(", ", parts)
                + ", " + HM.format(first) + "부터 " + min + "분" + (resolved == null ? "째 발생 중" : "간");
    }

    /** 서버별 직전 진단 요약 (처리 기록 초안의 근거) */
    private List<Map<String, Object>> diagnoses(List<Long> serverIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long sid : serverIds) {
            Last last = lastDiagnosis.get(sid);
            if (last == null || last.at().isBefore(Instant.now().minus(DIAGNOSIS_FRESH))) {
                continue;
            }
            JsonNode d = (JsonNode) last.result().get("diagnosis");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("server", last.result().get("server"));
            m.put("diagnosedAt", Alarm.fmt(last.at()));
            m.put("summary", d.path("summary").asString(""));
            m.put("causes", d.path("causes"));
            if (last.result().get("root") instanceof Map<?, ?> root) {
                m.put("rootCauseServer", root.get("server"));
                m.put("rootCause", root.get("cause"));
            }
            out.add(m);
        }
        return out;
    }

    /** 서버별 조치 이력을 한 줄씩 (누가 · 어느 서버 · 어떤 프로세스 · 무엇 · 결과) */
    private List<String> actionLines(List<Long> serverIds, Instant since) {
        return serverIds.stream().flatMap(sid -> actions.since(sid, since).stream())
                .map(a -> String.valueOf(a.get("createdAt")).substring(11, 16) + " " + a.get("by") + " 승인으로 " + a.get("server") + "의 " + a.get("name")
                        + "(PID " + a.get("pid") + ")를 " + a.get("actionKr") + " → " + statusKr(String.valueOf(a.get("status")))
                        // 성공 메시지는 앞 내용과 같아 생략, 실패·만료일 때만 이유를 붙임
                        + (a.get("message") == null || "DONE".equals(a.get("status")) ? "" : " (" + a.get("message") + ")"))
                .toList();
    }

    private static String statusKr(String s) {
        return switch (s) {
            case "DONE" -> "실행 완료";
            case "FAILED" -> "실패";
            case "EXPIRED" -> "만료(미실행)";
            case "SIMULATED" -> "시뮬레이션 기록";
            default -> "대기 중";
        };
    }

    /** AI 없이 사실만으로 만든 원인·결과 줄 */
    @SuppressWarnings("unchecked")
    private static String[] fallbackCauseResult(Map<String, Object> facts, List<Map<String, Object>> done) {
        List<Map<String, Object>> diag = new ArrayList<>((List<Map<String, Object>>) facts.get("diagnosis"));
        diag.addAll((List<Map<String, Object>>) facts.get("relatedDiagnosis"));
        String cause;
        if (!done.isEmpty()) {
            Map<String, Object> a = done.get(0);
            cause = a.get("server") + "의 " + a.get("name") + " 실행이 원인으로 추정됨 (진단 후 조치한 프로세스)";
        } else {
            cause = diag.stream().filter(d -> d.get("rootCause") != null).findFirst()
                    .map(d -> d.get("rootCauseServer") + " — " + d.get("rootCause") + " (추정)")
                    .orElse(diag.isEmpty() ? "원인 확인 필요" : diag.get(0).get("summary") + " (추정)");
        }
        String res;
        if ("아직 발생 중".equals(facts.get("resolvedAt"))) {
            res = "알림 아직 발생 중";
        } else {
            String at = String.valueOf(facts.get("resolvedAt"));
            res = (done.isEmpty() ? "" : done.get(0).get("name") + " " + done.get(0).get("actionKr") + " 후 ")
                    + (at.length() >= 16 ? at.substring(11, 16) : at) + " 알림 해제됨";
        }
        return new String[] { cause, res };
    }
}
