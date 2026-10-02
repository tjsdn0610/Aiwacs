package com.sysone.aiwacs.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sysone.aiwacs.alarm.AlarmService;
import com.sysone.aiwacs.policy.PolicyService;
import com.sysone.aiwacs.policy.PolicyService.ChangeResult;
import com.sysone.aiwacs.server.AgentReport;
import com.sysone.aiwacs.server.MonitoredServer;
import com.sysone.aiwacs.server.ServerService;

import tools.jackson.databind.JsonNode;

/**
 * AI 운영 도우미.
 * - 임계치 설정: AI는 자연어 → JSON "번역"만, 검증·저장은 PolicyService(코드)가 한다.
 * - 상태 진단: 판정은 코드가 이미 내린 값을 전달하고, AI는 해석만 한다.
 */
@Service
public class AiService {

    private static final String NOT_CONFIGURED = "AI가 설정되지 않았습니다 (AI 설정값 확인 필요).";
    private static final String NOT_UNDERSTOOD = "명령을 이해하지 못했습니다. 다시 말씀해 주세요.";
    private static final String AI_UNAVAILABLE = "AI 서버에 연결할 수 없습니다. 로컬 AI(Ollama)가 실행 중인지 확인해 주세요.";
    private static final String AI_BUSY = "AI 서버에 요청이 몰려 잠시 응답하지 못하고 있습니다. 잠시 후 다시 시도해 주세요.";

    private final AiClient ai;
    private final PolicyService policyService;
    private final ServerService servers;
    private final AlarmService alarmService;

    public AiService(AiClient ai, PolicyService policyService, ServerService servers,
                     AlarmService alarmService) {
        this.ai = ai;
        this.policyService = policyService;
        this.servers = servers;
        this.alarmService = alarmService;
    }

    // ===== AI 정책 설정: 임계치 변경(여러 개 동시, "*"로 전체/고객사 전체 일괄) + 정책 추가 + 정책 삭제(확인 후) =====
    public Map<String, Object> changeThreshold(String userMsg) {
        if (!ai.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }

        List<Map<String, String>> policyList = policyService.findAll().stream()
                .map(p -> Map.of("company", p.getCompany(), "name", p.getName()))
                .toList();

        String prompt = """
                너는 서버 모니터링 시스템의 알림 정책(임계치) 설정을 돕는 도우미다.
                사용자의 명령을 아래 JSON 배열 형식으로만 변환해라. 다른 말은 절대 하지 마라.

                현재 등록된 정책 목록:
                %s

                형식 (항상 배열로 답해라. 변경이 하나여도 배열 안에 하나 넣어라):
                [
                  {"action": "update", "company": "기업명", "policy": "정책명", "metric": "cpu|memory|disk", "level": "주의|경고|위험|장애", "value": 숫자}
                ]
                action: 임계치 변경은 "update", 새 정책 만들기는 "create", 정책 지우기는 "delete"
                - create: {"action": "create", "company": "기업명", "policy": "새 정책명"}
                  임계치도 함께 말했으면 create 뒤에 그 새 정책에 대한 update를 이어서 넣어라
                  예) "테라넷에 캐시서버 정책 만들어줘, CPU 경고 75" →
                      [{"action": "create", "company": "테라넷", "policy": "캐시서버 정책"},
                       {"action": "update", "company": "테라넷", "policy": "캐시서버 정책", "metric": "cpu", "level": "경고", "value": 75}]
                - delete: {"action": "delete", "company": "기업명", "policy": "정책명"} (metric/level/value 없음)
                  사용자가 정책 이름을 말하지 않았으면 policy는 ""로 둬라 (임의로 고르지 마라)

                규칙:
                - 사용자가 여러 항목을 한 번에 바꾸라고 하면, 각각을 배열의 원소로 만들어라
                - company/policy: 위 목록에서 가장 일치하는 것을 골라라 (오타나 구어체도 최대한 매칭)
                - 여러 정책을 한 번에: "모든/전체 정책"이면 company와 policy를 둘 다 "*"로,
                  한 기업의 모든 정책("○○ 정책 전부")이면 company는 기업명, policy는 "*"로 해라 (정책을 하나씩 나열하지 마라)
                  예) "모든 정책 CPU 장애 96%%로" → [{"company": "*", "policy": "*", "metric": "cpu", "level": "장애", "value": 96}]
                  예) "(기업명) 정책 전부 메모리 위험 93" → [{"company": "(기업명)", "policy": "*", "metric": "memory", "level": "위험", "value": 93}]
                  "전체/모든"이라고 하면 특정 기업을 고르지 말고 company도 반드시 "*"로 해라
                - metric: CPU는 "cpu", 메모리는 "memory", 디스크는 "disk"
                - level: 사용자가 말한 단어 그대로 "주의", "경고", "위험", "장애" 중 하나 (심각/크리티컬은 "장애")
                - value: 퍼센트 숫자만 (0~100)
                - 명령을 전혀 이해할 수 없으면 [{"error": "이해할 수 없는 명령입니다"}]

                사용자 명령: %s""".formatted(ai.toJson(policyList), userMsg);

        JsonNode parsed;
        try {
            parsed = ai.extractJson(ai.generate(prompt), true);
        } catch (AiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (AiClient.AiUnavailableException e) {
            return Map.of("ok", false, "reply", AI_UNAVAILABLE);
        } catch (Exception e) {
            return Map.of("ok", false, "reply", NOT_UNDERSTOOD);
        }

        // 하나짜리 객체로 와도 리스트로 통일
        List<JsonNode> commands = new ArrayList<>();
        if (parsed.isObject()) {
            commands.add(parsed);
        } else if (parsed.isArray()) {
            parsed.forEach(commands::add);
        }
        if (commands.isEmpty()) {
            return Map.of("ok", false, "reply", NOT_UNDERSTOOD);
        }

        List<String> changes = new ArrayList<>();
        List<String> fails = new ArrayList<>();
        List<Map<String, Object>> confirms = new ArrayList<>(); // 삭제 확인 요청 (화면에 [삭제]/[취소] 버튼)
        for (JsonNode cmd : commands) {
            if (!cmd.isObject()) {
                fails.add("잘못된 명령 형식입니다.");
                continue;
            }
            if (cmd.has("error")) {
                fails.add(cmd.path("error").asString(""));
                continue;
            }
            String action = cmd.path("action").asString("update");
            String company = cmd.path("company").asString("");
            String policy = cmd.path("policy").asString("");
            if ("create".equals(action)) {
                ChangeResult r = policyService.createPolicy(company, policy);
                (r.ok() ? changes : fails).add(r.message());
                continue;
            }
            if ("delete".equals(action)) {
                // AI 명령으로는 지우지 않는다 → 영향 받는 서버를 보여주고, 사용자가 [삭제]를 눌러야 지운다
                PolicyService.DeletePlan plan = policyService.planDelete(company, policy);
                if (plan.ok()) {
                    confirms.add(Map.of("policyId", plan.policyId(), "text", plan.message()));
                } else {
                    fails.add(plan.message());
                }
                continue;
            }
            Integer value = toPercent(cmd.path("value"));
            if (value == null) {
                fails.add("임계치 값을 이해하지 못했습니다.");
                continue;
            }
            // "*"(전체/고객사 전체)면 여러 정책이 한 번에 바뀌므로 결과도 여러 건
            for (ChangeResult r : policyService.changeThreshold(
                    company,
                    policy,
                    cmd.path("metric").asString(null),
                    toLevelCode(cmd.path("level").asString("")),
                    value)) {
                (r.ok() ? changes : fails).add(r.message());
            }
        }

        // 응답 메시지 조립
        List<String> parts = new ArrayList<>();
        if (!changes.isEmpty()) {
            parts.add((fails.isEmpty() ? "다음 항목을 처리했습니다:\n" : "일부 처리했습니다:\n") + bullets(changes));
        }
        if (!fails.isEmpty()) {
            parts.add(changes.isEmpty() && confirms.isEmpty() && fails.size() == 1
                    ? fails.get(0)
                    : "처리 못한 항목:\n" + bullets(fails));
        }
        if (parts.isEmpty() && confirms.isEmpty()) {
            parts.add("처리할 항목을 찾지 못했습니다.");
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", !changes.isEmpty() || !confirms.isEmpty());
        resp.put("reply", String.join("\n\n", parts));
        resp.put("confirm", confirms);
        return resp;
    }

    /**
     * AI가 준 레벨(한글)을 코드값으로 변환. 작은 모델이 한글→영어 번역에서 위험/장애를 헷갈려서
     * AI에게는 한글 그대로 받고, 변환은 코드가 한다. (영어로 와도 그대로 통과)
     */
    private static final Map<String, String> LEVEL_CODE =
            Map.of("주의", "caution", "경고", "warning", "위험", "danger", "장애", "critical");

    private static String toLevelCode(String level) {
        String l = level.trim();
        return LEVEL_CODE.getOrDefault(l, l);
    }

    /** AI가 준 value를 정수 퍼센트로 변환 (숫자 또는 "80", "80%" 같은 문자열 허용) */
    private static Integer toPercent(JsonNode v) {
        if (v.isNumber()) {
            return (int) Math.round(v.asDouble());
        }
        if (v.isString()) {
            try {
                return (int) Math.round(Double.parseDouble(v.asString().replace("%", "").strip()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String bullets(List<String> items) {
        return String.join("\n", items.stream().map(s -> "• " + s).toList());
    }

    // ===== AI 상태 진단 + 원인 프로세스 유추 =====
    public Map<String, Object> diagnose(Long serverId) {
        if (!ai.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }

        // 0) 선택한 서버의 최신 지표 (Agent가 보낸 값)
        MonitoredServer server = serverId == null ? null : servers.findById(serverId).orElse(null);
        if (server == null) {
            return Map.of("ok", false, "reply", "진단할 서버를 선택해 주세요.");
        }
        if (!servers.isOnline(serverId)) {
            return Map.of("ok", false, "reply",
                    "'" + server.label() + "' 서버가 오프라인 상태라 진단할 수 없습니다. Agent 실행 여부를 확인해 주세요.");
        }
        AgentReport report = servers.latest(serverId).orElseThrow().report();

        // 1) 현재 상태 + 판정 (판정은 코드가)
        Map<String, Map<String, Object>> status = servers.judge(serverId).orElseThrow();

        // 2) 프로세스 목록 (CPU/메모리 상위 5개)
        List<Map<String, Object>> topCpu = report.procs().stream()
                .sorted(Comparator.comparingDouble(AgentReport.Proc::cpu).reversed()).limit(5)
                .map(p -> ordered("name", p.name(), "cpu", p.cpu())).toList();
        List<Map<String, Object>> topMem = report.procs().stream()
                .sorted(Comparator.comparingDouble(AgentReport.Proc::mem).reversed()).limit(5)
                .map(p -> ordered("name", p.name(), "mem", p.mem())).toList();

        // 2-1) 세부 지표 + I/O·페이지폴트 초당 값 (AI 진단 정확도 향상용)
        Map<String, Object> detail = new LinkedHashMap<>();
        if (report.detail() != null) detail.putAll(report.detail());
        if (report.io() != null) detail.putAll(report.io());
        List<Map<String, Object>> topIo = report.topIo() != null ? report.topIo() : List.of();
        Map<String, Object> serverInfo = ordered("name", server.label(), "os", server.getOs());
        serverInfo.put("company", server.getCompany());
        serverInfo.put("policy", servers.summary(server).get("policyName")); // 판정 기준이 된 정책

        // 3) AI에게 해석 요청
        String prompt = """
                당신은 20년 경력의 시스템 성능 분석 전문가입니다.
                아래 서버 상태와 세부 지표를 종합 분석하되, 반드시 규칙을 지키세요.

                [분석 규칙]
                - 각 지표를 따로 보지 말고, 지표들 사이의 '인과관계(상관관계)'를 분석하세요.
                  예시: 메모리 부족 → 캐시(cached) 감소 → 페이지폴트 증가 → 디스크 I/O 증가 → 응답 저하
                  예시: Load Average가 코어 수보다 큼 → CPU 처리 대기 → 특정 프로세스 병목
                  예시: Swap 사용 시작 → 물리 메모리 고갈 신호 → 성능 급저하 위험
                - 상태 판정(정상/주의/경고/위험/장애)은 이미 시스템이 내렸습니다. 바꾸지 말고 해석만 하세요.
                - 원인을 단정하지 마세요. "~일 가능성이 있습니다", "~로 보입니다" 형태로만.
                - 프로세스 목록을 참고해 어떤 프로세스가 원인일 가능성이 있는지 짚으세요.
                - 페이지폴트·스왑·디스크 I/O 값이 인과관계를 뒷받침하는지 확인하고, 근거가 된 수치를 함께 언급하세요.
                  수치가 낮으면 해당 연결고리는 '현재는 뚜렷하지 않다'고 말하세요.
                - 전문적으로 분석하되, 결과 설명은 IT 비전문가도 이해하도록 쉬운 말로 풀어쓰세요.
                  (전문용어는 괄호로 쉽게 풀어서. 예: 페이지폴트(메모리에 없어 디스크에서 다시 읽는 현상))
                - 반드시 아래 JSON 형식으로만 답하세요. 다른 말 금지.

                [출력 형식]
                {"level": "정상|주의|경고|위험|장애", "summary": "지금 무슨 일이 일어나는지 쉬운 말로 1~2문장", "correlation": "지표들이 어떻게 서로 영향을 주는지 인과관계를 화살표(→)로 표현하고 쉽게 설명", "causes": ["가능성 있는 원인1", "원인2"], "check": "직접 확인해볼 방법을 쉽게", "action": "권장 조치를 단계별로 쉽게"}

                [서버 정보]
                %s

                [현재 상태 및 판정]
                %s

                [세부 지표]
                %s

                [세부 지표 설명] (_s로 끝나는 값은 최근 몇 초 동안 측정한 초당 값)
                - disk_read_mb_s / disk_write_mb_s: 디스크 읽기/쓰기 속도(MB/s)
                - disk_busy_percent: 가장 바쁜 디스크가 작업 중이던 시간 비율. 높으면 디스크 병목 가능성
                - disk_queue_length: 디스크 작업 대기열 길이. 계속 1 이상이면 작업이 밀리는 중
                - swap_page_in_s / swap_page_out_s: 스왑(디스크)과 메모리 사이에 오간 페이지 수
                - major_faults_s: 메모리에 없어 디스크까지 가서 읽어온 횟수. 높으면 메모리 부족 신호
                - minor_faults_s: 메모리 안에서 처리된 가벼운 폴트. 수천 단위도 흔하며 단독으로는 문제 아님

                [CPU 상위 프로세스]
                %s

                [메모리 상위 프로세스]
                %s

                [디스크 I/O 상위 프로세스] (io_kb_s: 읽기+쓰기 KB/s, major_faults_s: 해당 프로세스의 major 폴트/초)
                %s""".formatted(ai.toJson(serverInfo), ai.toJson(status), ai.toJson(detail),
                ai.toJson(topCpu), ai.toJson(topMem), ai.toJson(topIo));

        JsonNode result;
        try {
            result = ai.extractJson(ai.generate(prompt), false);
        } catch (AiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (AiClient.AiUnavailableException e) {
            return Map.of("ok", false, "reply", AI_UNAVAILABLE);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            return Map.of("ok", false, "reply", "진단 실패: " + e.getClass().getSimpleName() + ": "
                    + msg.substring(0, Math.min(200, msg.length())));
        }

        return Map.of("ok", true, "server", server.label(), "status", status, "diagnosis", result);
    }

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    // ===== AI 알림 그룹핑 (여러 지표 알림을 '하나의 사건'으로 묶고 조합을 해석) =====
    // 관찰: CPU 하나가 올라도 CPU / CPU Core / CPU User 가 각각 임계를 넘어 알림이 여러 건 뜬다.
    // AiWACS의 탐지·판정은 그대로 두고(원본 알림 보존), AI는 관제자에게 '사건 단위'로 접어 보여준다.
    private static final Map<String, String> METRIC_KR2 = Map.of("cpu", "CPU", "memory", "메모리", "disk", "디스크");

    /**
     * 현재 발생 알림을 사건 단위로 묶고 해석한다.
     * - 어떤 알림끼리 한 사건인지는 코드(AlarmService.events)가 정한다: 같은 서버 + 같은 자원 → 한 사건.
     *   (실측: CPU 한 번 오른 것이 CPU/CPU Core/CPU User × 주의·경고·장애로 불어나고, 발생 횟수·시각이 똑같았다)
     * - AI는 묶인 사건마다 제목·원인·조치만 쓴다. AI가 실패해도 묶음 자체는 그대로 보여준다.
     * (demo 인자는 예전 화면 호환용으로 남겨 두었고, 묶기는 항상 실제 발생 알림 기준)
     */
    public Map<String, Object> groupAlarms(boolean demo) {
        List<Map<String, Object>> alarms = currentAlarms();
        List<Map<String, Object>> events = alarmService.events();
        if (events.isEmpty()) {
            return Map.of("ok", true, "source", "current", "rawCount", 0,
                    "alarms", alarms, "result", Map.of("events", List.of()));
        }

        // AI 해석 (실패하면 코드 묶음 + 기본 문구로 보여준다)
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        String aiNote = null;
        if (!ai.isConfigured()) {
            aiNote = NOT_CONFIGURED;
        } else {
            try {
                JsonNode result = ai.extractJson(ai.generate(groupPrompt(events)), false);
                for (JsonNode n : result.path("events")) {
                    byKey.put(n.path("key").asString(""), n);
                }
            } catch (AiClient.AiBusyException e) {
                aiNote = AI_BUSY;
            } catch (AiClient.AiUnavailableException e) {
                aiNote = AI_UNAVAILABLE;
            } catch (Exception e) {
                aiNote = "AI 해석 실패: " + shortError(e);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            JsonNode n = byKey.get(String.valueOf(e.get("key")));
            String res = METRIC_KR2.getOrDefault(String.valueOf(e.get("resource")), String.valueOf(e.get("resource")));
            Map<String, Object> ev = new LinkedHashMap<>(e);
            ev.put("title", text(n, "title", e.get("server") + " " + res + " 사건"));
            ev.put("count", e.get("alarmCount"));    // 예전 화면 호환: 묶은 알림 수
            ev.put("members", e.get("alarms"));       // 예전 화면 호환: 묶인 알림 목록
            ev.put("cause", text(n, "cause", "(AI 해석 없음) 같은 서버·같은 자원에서 동시에 발생한 알림을 한 사건으로 묶었습니다."));
            ev.put("action", text(n, "action", "-"));
            out.add(ev);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("source", "current");
        resp.put("rawCount", alarms.size());
        resp.put("alarms", alarms);
        resp.put("result", Map.of("events", out));
        if (aiNote != null) {
            resp.put("aiNote", aiNote);
        }
        return resp;
    }

    private static String text(JsonNode n, String field, String fallback) {
        String v = n == null ? "" : n.path(field).asString("");
        return v.isBlank() ? fallback : v;
    }

    private String groupPrompt(List<Map<String, Object>> events) {
        return """
                당신은 관제(NOC) 도우미입니다. 아래는 AiWACS 알림을 시스템이 이미 '사건' 단위로 묶어 둔 것입니다.
                묶음과 레벨 판정은 정확하니 바꾸지 마세요. 사건마다 관제자가 바로 이해할 제목·원인·조치만 쓰세요.

                [알아둘 점]
                - AiWACS는 한 자원을 여러 세부 지표(CPU / CPU Core / CPU User)로, 그리고 레벨(주의·경고·위험·장애)마다
                  따로 알림을 냅니다. 그래서 alarmCount가 커도 실제로는 한 번의 상승일 수 있습니다.
                - byLevel은 레벨별 알림 수, occurrences는 발생 횟수 합계, firstAt~lastAt은 사건 구간입니다.
                - 세부 지표 조합으로 원인 유형을 추정하세요:
                  · User·Core·전체가 함께 → 전 코어 계산 부하
                  · Core만 높고 전체는 낮음 → 단일 스레드 병목 가능성
                  · Wait만 높음 → CPU가 아니라 디스크/IO 대기 가능성
                  · System만 높음 → 커널/네트워크/컨텍스트 스위치 폭주 가능성
                - 같은 서버에 다른 자원 사건이 함께 있으면(예: CPU와 메모리) 연관 가능성을 짚으세요.
                - 원인은 단정하지 말고 "가능성"으로. 반드시 JSON만 답하세요.

                [출력 형식] key는 입력의 key를 그대로 쓰세요.
                {"events": [{"key": "입력 key", "title": "사건 요약(예: server1 CPU 장애 — 알림 12건이 한 번의 CPU 상승)", "cause": "원인 추정", "action": "권장 조치"}]}

                [사건 목록]
                %s""".formatted(ai.toJson(events));
    }

    /** 종(알림) 배지·목록용: AI 호출 없이 발생 알림 목록만 반환 */
    public List<Map<String, Object>> listAlarms(boolean demo) {
        return demo ? demoAlarms() : currentAlarms();
    }

    /** 현재 발생 중인 실제 알람 (알람 엔진 기준) */
    private List<Map<String, Object>> currentAlarms() {
        return alarmService.activeAlarms();
    }

    /** 부하 실험에서 실제로 관찰한 알림 세트 (CPU 한 사건이 세 지표로 분리되어 뜬 상황) */
    private List<Map<String, Object>> demoAlarms() {
        return List.of(
                alarm("server1", "테라넷", "CPU (Core) >= 40%", "경고", 100),
                alarm("server1", "테라넷", "CPU User >= 40%", "경고", 98),
                alarm("server1", "테라넷", "CPU User (Core) >= 40%", "경고", 99),
                alarm("server1", "테라넷", "메모리 사용률 >= 80%", "주의", 82),
                alarm("server2", "ABC", "디스크 사용률 >= 80%", "주의", 88));
    }

    private static Map<String, Object> alarm(String server, String company, String metric, String level, int value) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("server", server);
        a.put("company", company);
        a.put("metric", metric);
        a.put("level", level);
        a.put("value", value);
        return a;
    }

    private static String shortError(Exception e) {
        String msg = String.valueOf(e.getMessage());
        return e.getClass().getSimpleName() + ": " + msg.substring(0, Math.min(200, msg.length()));
    }
}
