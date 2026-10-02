package com.sysone.aiwacs.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sysone.aiwacs.alarm.AlarmService;
import com.sysone.aiwacs.policy.PolicyService;
import com.sysone.aiwacs.policy.PolicyService.ChangeResult;

import tools.jackson.databind.JsonNode;

/**
 * AI 운영 도우미.
 * - 임계치 설정: AI는 자연어 → JSON "번역"만, 검증·저장은 PolicyService(코드)가 한다.
 * - 알림 묶기: 묶음은 코드가, AI는 사건마다 제목·원인·조치만 쓴다.
 * (상태 진단·조치 제안·처리 기록 초안은 DiagnosisService)
 */
@Service
public class AiService {

    static final String NOT_CONFIGURED = "AI가 설정되지 않았습니다 (AI 설정값 확인 필요).";
    static final String NOT_UNDERSTOOD = "명령을 이해하지 못했습니다. 다시 말씀해 주세요.";
    static final String AI_UNAVAILABLE = "AI 서버에 연결할 수 없습니다. 로컬 AI(Ollama)가 실행 중인지 확인해 주세요.";
    static final String AI_BUSY = "AI 서버에 요청이 몰려 잠시 응답하지 못하고 있습니다. 잠시 후 다시 시도해 주세요.";

    private final AiClient ai;
    private final PolicyService policyService;
    private final AlarmService alarmService;

    public AiService(AiClient ai, PolicyService policyService, AlarmService alarmService) {
        this.ai = ai;
        this.policyService = policyService;
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
            // AI가 고객사를 "전체"로 넓혔는데 사용자가 고객사 이름을 말했다면, 그 고객사로만 좁힌다
            // (예: "테라넷 정책 전부"를 AI가 company "*"로 잘못 옮겨도 ABC 정책은 바뀌지 않게 — 범위 결정은 코드가)
            List<String> companies = List.of(company);
            if (company.isBlank() || PolicyService.ALL.equals(company)) {
                List<String> named = policyService.companies().stream().filter(userMsg::contains).toList();
                if (!named.isEmpty()) {
                    companies = named;
                }
            }
            // "*"(전체/고객사 전체)면 여러 정책이 한 번에 바뀌므로 결과도 여러 건
            for (String c : companies) {
                for (ChangeResult r : policyService.changeThreshold(
                        c,
                        policy,
                        cmd.path("metric").asString(null),
                        toLevelCode(cmd.path("level").asString("")),
                        value)) {
                    (r.ok() ? changes : fails).add(r.message());
                }
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

    // ===== AI 알림 그룹핑 (여러 지표 알림을 '하나의 사건'으로 묶고 조합을 해석) =====
    // 관찰: CPU 하나가 올라도 CPU / CPU Core / CPU User 가 각각 임계를 넘어 알림이 여러 건 뜬다.
    // AiWACS의 탐지·판정은 그대로 두고(원본 알림 보존), AI는 관제자에게 '사건 단위'로 접어 보여준다.
    private static final Map<String, String> METRIC_KR2 = Map.of("cpu", "CPU", "memory", "메모리", "disk", "디스크");

    /**
     * 현재 발생 알림을 사건 단위로 묶고 해석한다.
     * - 어떤 알림끼리 한 사건인지는 코드(AlarmService.events)가 정한다: 같은 서버 + 같은 자원 → 한 사건.
     *   (실측: CPU 한 번 오른 것이 CPU/CPU Core/CPU User × 주의·경고·장애로 불어나고, 발생 횟수·시각이 똑같았다)
     * - AI는 묶인 사건마다 제목·원인·조치만 쓴다. AI가 실패해도 묶음 자체는 그대로 보여준다.
     * (demo 인자는 예전 화면 호환용으로 남겨 두었고, 묶기는 항상 실제 알림 기준)
     *
     * @param includeOpenWork true면 해제됐어도 처리가 끝나지 않은 알림까지 묶는다 (알림 내역 화면용)
     */
    public Map<String, Object> groupAlarms(boolean demo, boolean includeOpenWork) {
        List<Map<String, Object>> alarms = currentAlarms();
        List<Map<String, Object>> events = alarmService.events(includeOpenWork);
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
        resp.put("rawCount", events.stream().mapToInt(e -> (int) e.get("alarmCount")).sum()); // 묶인 원본 알림 수
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

    static String shortError(Exception e) {
        String msg = String.valueOf(e.getMessage());
        return e.getClass().getSimpleName() + ": " + msg.substring(0, Math.min(200, msg.length()));
    }
}
