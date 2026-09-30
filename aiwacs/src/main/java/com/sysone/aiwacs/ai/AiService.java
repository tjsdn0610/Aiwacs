package com.sysone.aiwacs.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sysone.aiwacs.history.MetricHistoryService;
import com.sysone.aiwacs.policy.Policy;
import com.sysone.aiwacs.policy.PolicyService;
import com.sysone.aiwacs.policy.PolicyService.ChangeResult;
import com.sysone.aiwacs.policy.Threshold;
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

    private static final String NOT_CONFIGURED = "AI가 설정되지 않았습니다 (API 키 확인 필요).";
    private static final String NOT_UNDERSTOOD = "명령을 이해하지 못했습니다. 다시 말씀해 주세요.";
    private static final String AI_BUSY = "AI 서버에 요청이 몰려 잠시 응답하지 못하고 있습니다. 잠시 후 다시 시도해 주세요.";

    private final GeminiClient gemini;
    private final PolicyService policyService;
    private final ServerService servers;
    private final MetricHistoryService history;
    private final HandlingNoteStore notes;

    public AiService(GeminiClient gemini, PolicyService policyService, ServerService servers,
                     MetricHistoryService history, HandlingNoteStore notes) {
        this.gemini = gemini;
        this.policyService = policyService;
        this.servers = servers;
        this.history = history;
        this.notes = notes;
    }

    // ===== AI 임계치 변경 (기업+정책 지정, 여러 개 동시 가능) =====
    public Map<String, Object> changeThreshold(String userMsg) {
        if (!gemini.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }

        List<Map<String, String>> policyList = policyService.findAll().stream()
                .map(p -> Map.of("company", p.getCompany(), "name", p.getName()))
                .toList();

        String prompt = """
                너는 서버 모니터링 시스템의 임계치 설정을 돕는 도우미다.
                사용자의 명령을 아래 JSON 배열 형식으로만 변환해라. 다른 말은 절대 하지 마라.

                현재 등록된 정책 목록:
                %s

                형식 (항상 배열로 답해라. 변경이 하나여도 배열 안에 하나 넣어라):
                [
                  {"company": "기업명", "policy": "정책명", "metric": "cpu|memory|disk", "level": "warn|danger", "value": 숫자}
                ]

                규칙:
                - 사용자가 여러 항목을 한 번에 바꾸라고 하면, 각각을 배열의 원소로 만들어라
                - company/policy: 위 목록에서 가장 일치하는 것을 골라라 (오타나 구어체도 최대한 매칭)
                - metric: CPU는 "cpu", 메모리는 "memory", 디스크는 "disk"
                - level: 주의/경고는 "warn", 위험/심각은 "danger"
                - value: 퍼센트 숫자만 (0~100)
                - 명령을 전혀 이해할 수 없으면 [{"error": "이해할 수 없는 명령입니다"}]

                사용자 명령: %s""".formatted(gemini.toJson(policyList), userMsg);

        JsonNode parsed;
        try {
            parsed = gemini.extractJson(gemini.generate(prompt), true);
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
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
        for (JsonNode cmd : commands) {
            if (!cmd.isObject()) {
                fails.add("잘못된 명령 형식입니다.");
                continue;
            }
            if (cmd.has("error")) {
                fails.add(cmd.path("error").asString(""));
                continue;
            }
            Integer value = toPercent(cmd.path("value"));
            if (value == null) {
                fails.add("임계치 값을 이해하지 못했습니다.");
                continue;
            }
            ChangeResult r = policyService.changeThreshold(
                    cmd.path("company").asString(""),
                    cmd.path("policy").asString(""),
                    cmd.path("metric").asString(null),
                    cmd.path("level").asString(null),
                    value);
            (r.ok() ? changes : fails).add(r.message());
        }

        // 응답 메시지 조립
        if (!changes.isEmpty() && fails.isEmpty()) {
            return Map.of("ok", true, "reply", "다음 항목을 변경했습니다:\n" + bullets(changes));
        }
        if (!changes.isEmpty()) {
            return Map.of("ok", true, "reply",
                    "일부 변경했습니다:\n" + bullets(changes) + "\n\n처리 못한 항목:\n" + bullets(fails));
        }
        return Map.of("ok", false, "reply", fails.isEmpty() ? "변경할 항목을 찾지 못했습니다." : String.join(" ", fails));
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
        if (!gemini.isConfigured()) {
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

        // 3) Gemini에게 해석 요청
        String prompt = """
                당신은 20년 경력의 시스템 성능 분석 전문가입니다.
                아래 서버 상태와 세부 지표를 종합 분석하되, 반드시 규칙을 지키세요.

                [분석 규칙]
                - 각 지표를 따로 보지 말고, 지표들 사이의 '인과관계(상관관계)'를 분석하세요.
                  예시: 메모리 부족 → 캐시(cached) 감소 → 페이지폴트 증가 → 디스크 I/O 증가 → 응답 저하
                  예시: Load Average가 코어 수보다 큼 → CPU 처리 대기 → 특정 프로세스 병목
                  예시: Swap 사용 시작 → 물리 메모리 고갈 신호 → 성능 급저하 위험
                - 상태 판정(정상/주의/위험)은 이미 시스템이 내렸습니다. 바꾸지 말고 해석만 하세요.
                - 원인을 단정하지 마세요. "~일 가능성이 있습니다", "~로 보입니다" 형태로만.
                - 프로세스 목록을 참고해 어떤 프로세스가 원인일 가능성이 있는지 짚으세요.
                - 페이지폴트·스왑·디스크 I/O 값이 인과관계를 뒷받침하는지 확인하고, 근거가 된 수치를 함께 언급하세요.
                  수치가 낮으면 해당 연결고리는 '현재는 뚜렷하지 않다'고 말하세요.
                - 전문적으로 분석하되, 결과 설명은 IT 비전문가도 이해하도록 쉬운 말로 풀어쓰세요.
                  (전문용어는 괄호로 쉽게 풀어서. 예: 페이지폴트(메모리에 없어 디스크에서 다시 읽는 현상))
                - 반드시 아래 JSON 형식으로만 답하세요. 다른 말 금지.

                [출력 형식]
                {"level": "정상|주의|위험", "summary": "지금 무슨 일이 일어나는지 쉬운 말로 1~2문장", "correlation": "지표들이 어떻게 서로 영향을 주는지 인과관계를 화살표(→)로 표현하고 쉽게 설명", "causes": ["가능성 있는 원인1", "원인2"], "check": "직접 확인해볼 방법을 쉽게", "action": "권장 조치를 단계별로 쉽게"}

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
                %s""".formatted(gemini.toJson(serverInfo), gemini.toJson(status), gemini.toJson(detail),
                gemini.toJson(topCpu), gemini.toJson(topMem), gemini.toJson(topIo));

        JsonNode result;
        try {
            result = gemini.extractJson(gemini.generate(prompt), false);
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            return Map.of("ok", false, "reply", "진단 실패: " + e.getClass().getSimpleName() + ": "
                    + msg.substring(0, Math.min(200, msg.length())));
        }

        return Map.of("ok", true, "server", server.label(), "status", status, "diagnosis", result);
    }

    // ===== AI 운영 브리핑 (전 서버 상황 요약 + 우선순위 조치) =====
    // 여러 대의 서버를 한 번에 훑어, 신입 운영자가 "지금 무엇부터 봐야 하는지" 알 수 있게 정리한다.
    // 판정·집계는 코드가(설계 원칙 "판정은 코드"), 우선순위와 원인·조치 해석은 AI가 한다.
    public Map<String, Object> briefing() {
        if (!gemini.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }

        int online = 0, offline = 0, danger = 0, caution = 0, normal = 0;
        List<Map<String, Object>> serverData = new ArrayList<>();

        for (MonitoredServer s : servers.findAll()) {
            Long id = s.getId();
            // 오프라인(10초간 수신 없음) 서버는 판정하지 않는다 — 마지막 값으로 정상/위험을 말하면 오해 소지
            Map<String, Map<String, Object>> judged = servers.isOnline(id)
                    ? servers.judge(id).orElse(null) : null;
            if (judged == null) {
                offline++;
                continue;
            }
            online++;
            String worst = PolicyService.worst(judged);
            switch (worst) {
                case "위험" -> danger++;
                case "주의" -> caution++;
                default -> normal++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("server", s.label());
            row.put("company", s.getCompany());
            row.put("policy", servers.summary(s).get("policyName")); // 판정 기준이 된 정책
            row.put("level", worst);
            row.put("metrics", judged); // cpu/memory/disk 각각 value + status

            // 주의·위험 서버에만 근본원인 판단 재료(세부지표·상위 프로세스·최근 추세)를 담는다.
            // 정상 서버까지 다 담으면 프롬프트가 불필요하게 커진다.
            if (!"정상".equals(worst)) {
                servers.latest(id).ifPresent(snap -> {
                    AgentReport report = snap.report();
                    Map<String, Object> detail = new LinkedHashMap<>();
                    if (report.detail() != null) detail.putAll(report.detail());
                    if (report.io() != null) detail.putAll(report.io());
                    if (!detail.isEmpty()) row.put("detail", detail);
                    row.put("topCpu", report.procs().stream()
                            .sorted(Comparator.comparingDouble(AgentReport.Proc::cpu).reversed()).limit(3)
                            .map(p -> ordered("name", p.name(), "cpu", p.cpu())).toList());
                    row.put("topMem", report.procs().stream()
                            .sorted(Comparator.comparingDouble(AgentReport.Proc::mem).reversed()).limit(3)
                            .map(p -> ordered("name", p.name(), "mem", p.mem())).toList());
                });
                // 최근 80초 추세: 값이 급증하는 중인지, 계속 높은 상태인지 구분하는 근거
                List<Map<String, Object>> recent = history.recent(id);
                if (!recent.isEmpty()) {
                    row.put("recent", recent);
                }
            }
            serverData.add(row);
        }

        // 전체 대표 상태와 집계는 코드가 결정한다 (일관성).
        String overallLevel = danger > 0 ? "위험" : caution > 0 ? "주의" : "정상";
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("level", overallLevel);
        overall.put("total", online + offline);
        overall.put("online", online);
        overall.put("offline", offline);
        overall.put("danger", danger);
        overall.put("caution", caution);
        overall.put("normal", normal);

        if (online == 0) {
            return Map.of("ok", true, "overall", overall, "briefing", Map.of(
                    "summary", "현재 온라인 상태인 서버가 없어 브리핑할 내용이 없습니다. Agent 실행 여부를 확인해 주세요.",
                    "priorities", List.of(),
                    "watch", ""));
        }

        String prompt = """
                당신은 20년 경력의 시스템 운영 총괄입니다. 여러 서버의 현재 상태를 한 번에 살펴보고,
                신입 운영자가 "지금 무엇부터 봐야 하는지" 알 수 있도록 교대 브리핑을 작성하세요.

                [작성 규칙]
                - 상태 판정(정상/주의/위험)과 집계는 이미 시스템이 코드로 내렸습니다. 바꾸지 말고 해석만 하세요.
                - 주의·위험 서버를 급한 순서로 정리하고(위험이 주의보다 먼저), 각 서버마다 '가능성 있는 원인'과 '권장 조치'를 제시하세요.
                - 원인은 단정하지 마세요. "~일 가능성이 있습니다" 형태로, 근거가 된 수치(세부지표·프로세스·최근 추세)를 함께 언급하세요.
                - 최근 80초 추세(recent)를 보고 값이 급증하는 중인지, 계속 높은 상태인지 구분해 언급하세요.
                - 정상 서버는 개별로 나열하지 말고, 지금은 괜찮지만 지켜볼 만한 점이 있으면 watch에 한 줄로 적으세요.
                - IT 비전문가도 이해하도록 쉬운 말로 쓰되, 전문용어는 괄호로 풀어주세요.
                - 반드시 아래 JSON 형식으로만 답하세요. 다른 말 금지.

                [출력 형식]
                {"summary": "전 서버 상황을 1~2문장으로", "priorities": [{"server": "서버 이름", "level": "주의|위험", "issue": "무엇이 문제인지 쉽게", "cause": "가능성 있는 원인(근거 수치 포함)", "action": "권장 조치를 단계로"}], "watch": "지금은 정상이나 지켜볼 서버/지표(없으면 빈 문자열)"}

                [전체 집계] (코드가 판정)
                %s

                [서버별 현재 상태] (주의·위험 서버에는 세부지표·상위 프로세스·최근 80초 추세 포함)
                %s""".formatted(gemini.toJson(overall), gemini.toJson(serverData));

        JsonNode result;
        try {
            result = gemini.extractJson(gemini.generate(prompt), false);
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            return Map.of("ok", false, "reply", "브리핑 생성 실패: " + e.getClass().getSimpleName() + ": "
                    + msg.substring(0, Math.min(200, msg.length())));
        }

        return Map.of("ok", true, "overall", overall, "briefing", result);
    }

    // ===== AI 원인 추적 (한 서버의 여러 신호를 자동으로 모아 원인 사슬로 연결) =====
    public Map<String, Object> rootcause(Long serverId) {
        if (!gemini.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }
        MonitoredServer server = serverId == null ? null : servers.findById(serverId).orElse(null);
        if (server == null) {
            return Map.of("ok", false, "reply", "분석할 서버를 선택해 주세요.");
        }
        if (!servers.isOnline(serverId)) {
            return Map.of("ok", false, "reply",
                    "'" + server.label() + "' 서버가 오프라인 상태라 원인을 추적할 수 없습니다.");
        }
        AgentReport report = servers.latest(serverId).orElseThrow().report();
        Map<String, Map<String, Object>> status = servers.judge(serverId).orElseThrow();

        Map<String, Object> detail = new LinkedHashMap<>();
        if (report.detail() != null) detail.putAll(report.detail());
        if (report.io() != null) detail.putAll(report.io());
        List<Map<String, Object>> topCpu = report.procs().stream()
                .sorted(Comparator.comparingDouble(AgentReport.Proc::cpu).reversed()).limit(3)
                .map(p -> ordered("name", p.name(), "cpu", p.cpu())).toList();
        List<Map<String, Object>> topMem = report.procs().stream()
                .sorted(Comparator.comparingDouble(AgentReport.Proc::mem).reversed()).limit(3)
                .map(p -> ordered("name", p.name(), "mem", p.mem())).toList();
        List<Map<String, Object>> recent = history.recent(serverId);

        String prompt = """
                당신은 20년 경력의 시스템 성능 분석 전문가입니다. 한 서버의 여러 신호를 종합해 원인을 추적하세요.
                이 환경에서 볼 수 있는 신호는 지표·상위 프로세스·세부지표·최근 80초 추세뿐입니다.
                (로그·세션·네트워크 경로는 이 데모 환경에서는 수집 대상이 아니므로 언급하지 마세요.)

                [규칙]
                - 판정(정상/주의/위험)은 코드가 이미 내렸습니다. 바꾸지 말고 해석만 하세요.
                - 신호들 사이의 인과관계를 화살표로 이어 원인 사슬(chain)을 만드세요.
                - 근거가 된 수치를 함께 쓰고, 원인은 단정하지 말고 "가능성" 형태로 쓰세요.
                - 쉬운 말로 쓰되 전문용어는 괄호로 풀어주세요. 반드시 JSON만 답하세요.

                [출력 형식]
                {"signals": [{"source": "지표|프로세스|메모리|디스크|추세", "detail": "값 요약", "hit": true 또는 false}], "chain": ["단계1", "단계2", "단계3"], "interpretation": "원인 해석 1~2문장", "action": "확인 방법과 조치를 단계로"}

                [현재 상태 및 판정]
                %s
                [세부 지표]
                %s
                [CPU 상위 프로세스]
                %s
                [메모리 상위 프로세스]
                %s
                [최근 80초 추세]
                %s""".formatted(gemini.toJson(status), gemini.toJson(detail),
                gemini.toJson(topCpu), gemini.toJson(topMem), gemini.toJson(recent));

        JsonNode result;
        try {
            result = gemini.extractJson(gemini.generate(prompt), false);
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (Exception e) {
            return Map.of("ok", false, "reply", "원인 추적 실패: " + shortError(e));
        }
        return Map.of("ok", true, "server", server.label(), "status", status, "result", result);
    }

    // ===== AI 처리내역 초안 (알림 해제 시 처리 내용을 AI가 먼저 써 준다) =====
    public Map<String, Object> noteDraft(Long serverId) {
        if (!gemini.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }
        MonitoredServer server = serverId == null ? null : servers.findById(serverId).orElse(null);
        if (server == null) {
            return Map.of("ok", false, "reply", "서버를 선택해 주세요.");
        }
        if (!servers.isOnline(serverId)) {
            return Map.of("ok", false, "reply", "'" + server.label() + "' 서버가 오프라인 상태입니다.");
        }
        Map<String, Map<String, Object>> status = servers.judge(serverId).orElseThrow();
        AgentReport report = servers.latest(serverId).orElseThrow().report();
        List<Map<String, Object>> topCpu = report.procs().stream()
                .sorted(Comparator.comparingDouble(AgentReport.Proc::cpu).reversed()).limit(3)
                .map(p -> ordered("name", p.name(), "cpu", p.cpu())).toList();

        String prompt = """
                당신은 운영 담당자를 돕는 도우미입니다. 방금 처리한 알림의 '처리 내용'을 담당자가 검토·수정할 수 있게 초안으로 작성하세요.
                아래 형식의 일반 텍스트로만 쓰세요(JSON·코드블록 금지). 원인은 단정하지 말고 "추정"으로,
                아직 확인 안 된 부분은 후속 확인 항목으로 남기세요.

                [원인] (근거 수치와 함께 추정)
                [조치] (실제로 한 것으로 보이는 조치)
                [후속] (확인·재발 방지 항목)

                [서버] %s (%s)
                [현재 상태 및 판정] %s
                [CPU 상위 프로세스] %s""".formatted(
                server.label(), server.getCompany(), gemini.toJson(status), gemini.toJson(topCpu));

        String draft;
        try {
            draft = gemini.generate(prompt).strip()
                    .replace("```", "").strip();
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (Exception e) {
            return Map.of("ok", false, "reply", "초안 생성 실패: " + shortError(e));
        }
        return Map.of("ok", true, "server", server.label(), "draft", draft);
    }

    /** 담당자가 검토·승인한 처리 내용을 지식 베이스에 저장 */
    public Map<String, Object> saveNote(Long serverId, String content) {
        if (content == null || content.isBlank()) {
            return Map.of("ok", false, "reply", "저장할 처리 내용이 없습니다.");
        }
        MonitoredServer server = serverId == null ? null : servers.findById(serverId).orElse(null);
        String label = server != null ? server.label() : "미지정";
        notes.add(serverId, label, content.strip());
        return Map.of("ok", true, "reply", "처리내역을 저장했습니다. 다음 유사 장애 대응에 활용됩니다.");
    }

    public List<Map<String, Object>> noteHistory() {
        return notes.recent(10);
    }

    // ===== AI 정책 튜닝 추천 (실측 이력 vs 임계치 비교로 오탐/미탐 탐지) =====
    public Map<String, Object> policyTuning() {
        if (!gemini.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MonitoredServer s : servers.findAll()) {
            Map<String, double[]> stat = history.stats(s.getId(), 7);
            if (stat.isEmpty()) {
                continue;
            }
            Policy policy = policyService.policyFor(s.getPolicyId(), s.getCompany()).orElse(null);
            if (policy == null) {
                continue;
            }
            for (String metric : List.of("cpu", "memory", "disk")) {
                double[] v = stat.get(metric);
                Threshold th = policy.threshold(metric);
                if (v == null || th == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("server", s.label());
                row.put("company", policy.getCompany());
                row.put("policy", policy.getName());
                row.put("metric", metric);
                row.put("avg", v[0]);
                row.put("max", v[1]);
                row.put("min", v[2]);
                row.put("warn", th.getWarn());
                row.put("danger", th.getDanger());
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return Map.of("ok", true, "result", Map.of(
                    "summary", "비교할 지표 이력이 아직 없습니다. Agent가 데이터를 수집한 뒤 다시 시도해 주세요.",
                    "items", List.of()));
        }

        String prompt = """
                당신은 모니터링 임계치 튜닝 전문가입니다. 각 정책의 '최근 7일 실측(avg/max/min)'과 '현재 임계치(warn/danger)'를
                비교해 오탐(너무 민감해 알림이 상시 발생)·미탐(너무 둔감해 놓칠 위험)·적정을 진단하고 조정값을 제안하세요.

                [규칙]
                - 실측 평균이 warn을 상시 넘으면 오탐 위험, 실측 최대가 danger보다 크게 낮으면 미탐 위험 가능성입니다.
                - 제안값은 0~100 범위의 정수로, 근거(수치)를 함께 쓰세요. 적정이면 유지 권장으로 두세요.
                - 반드시 JSON만 답하세요.

                [출력 형식]
                {"summary": "전체 한 줄 요약", "items": [{"company": "고객사", "policy": "정책명", "metric": "cpu|memory|disk", "verdict": "오탐 위험|미탐 위험|적정", "reason": "근거(수치 포함)", "suggestWarn": 정수 또는 null, "suggestDanger": 정수 또는 null}]}

                [실측 vs 임계치]
                %s""".formatted(gemini.toJson(rows));

        JsonNode result;
        try {
            result = gemini.extractJson(gemini.generate(prompt), false);
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (Exception e) {
            return Map.of("ok", false, "reply", "정책 점검 실패: " + shortError(e));
        }
        return Map.of("ok", true, "result", result);
    }

    /** 튜닝 추천을 실제 적용 (검증·저장은 기존 PolicyService가) */
    public Map<String, Object> applyTuning(String company, String policy, String metric, String level, int value) {
        ChangeResult r = policyService.changeThreshold(company, policy, metric, level, value);
        return Map.of("ok", r.ok(), "reply", r.message());
    }

    // ===== AI 서술형 보고서 / 교대 인수인계 =====
    public Map<String, Object> report(int hours) {
        if (!gemini.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }
        int online = 0, offline = 0, danger = 0, caution = 0, normal = 0;
        List<Map<String, Object>> serverData = new ArrayList<>();
        for (MonitoredServer s : servers.findAll()) {
            Long id = s.getId();
            Map<String, Map<String, Object>> judged = servers.isOnline(id)
                    ? servers.judge(id).orElse(null) : null;
            if (judged == null) {
                offline++;
                continue;
            }
            online++;
            String worst = PolicyService.worst(judged);
            switch (worst) {
                case "위험" -> danger++;
                case "주의" -> caution++;
                default -> normal++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("server", s.label());
            row.put("company", s.getCompany());
            row.put("level", worst);
            row.put("now", judged);
            Map<String, double[]> stat = history.stats(id, 1);
            if (!stat.isEmpty()) {
                Map<String, Object> trend = new LinkedHashMap<>();
                stat.forEach((k, v) -> trend.put(k, Map.of("avg", v[0], "max", v[1], "min", v[2])));
                row.put("todayTrend", trend);
            }
            serverData.add(row);
        }
        String overallLevel = danger > 0 ? "위험" : caution > 0 ? "주의" : "정상";
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("level", overallLevel);
        overall.put("total", online + offline);
        overall.put("online", online);
        overall.put("offline", offline);
        overall.put("danger", danger);
        overall.put("caution", caution);
        overall.put("normal", normal);

        if (online == 0) {
            return Map.of("ok", true, "overall", overall, "period", hours + "시간", "result", Map.of(
                    "summary", "온라인 서버가 없어 보고할 내용이 없습니다.",
                    "trends", "", "open", "", "handover", "Agent 실행 여부를 확인해 주세요."));
        }

        String prompt = """
                당신은 시스템 운영 총괄입니다. 최근 %d시간 운영 상황을 교대 근무자에게 넘길 인수인계 보고서로 작성하세요.
                (이 환경에는 별도 알림 이벤트 로그가 없으므로, 현재 상태와 오늘 자원 추세(todayTrend: 평균/최대/최소)를 근거로 씁니다.)

                [규칙]
                - 판정·집계는 코드가 내렸습니다. 해석만 하세요. 원인은 단정하지 말고 근거 수치를 함께 쓰세요.
                - 차트가 아니라 '문장'으로, 다음 근무자가 바로 이해하도록 쉽게 쓰세요. 반드시 JSON만 답하세요.

                [출력 형식]
                {"summary": "한눈에 1~2문장", "trends": "자원 추세 요약", "open": "지금 주의/위험이라 지켜봐야 할 서버", "handover": "다음 근무자 인계 사항"}

                [전체 집계]
                %s
                [서버별 현재 상태 + 오늘 추세]
                %s""".formatted(hours, gemini.toJson(overall), gemini.toJson(serverData));

        JsonNode result;
        try {
            result = gemini.extractJson(gemini.generate(prompt), false);
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (Exception e) {
            return Map.of("ok", false, "reply", "보고서 생성 실패: " + shortError(e));
        }
        return Map.of("ok", true, "overall", overall, "period", hours + "시간", "result", result);
    }

    // ===== AI 자연어 질의 (말로 물으면 현재 서버 데이터에서 답을 찾아준다) =====
    public Map<String, Object> query(String text) {
        if (!gemini.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }
        if (text == null || text.isBlank()) {
            return Map.of("ok", false, "reply", "질문을 입력해 주세요.");
        }
        List<Map<String, Object>> fleet = new ArrayList<>();
        for (MonitoredServer s : servers.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("server", s.label());
            row.put("company", s.getCompany());
            boolean online = servers.isOnline(s.getId());
            row.put("online", online);
            if (online) {
                Map<String, Map<String, Object>> judged = servers.judge(s.getId()).orElse(null);
                if (judged != null) {
                    row.put("cpu", judged.get("cpu").get("value"));
                    row.put("memory", judged.get("memory").get("value"));
                    row.put("disk", judged.get("disk").get("value"));
                    row.put("level", PolicyService.worst(judged));
                }
            }
            fleet.add(row);
        }

        String prompt = """
                당신은 모니터링 데이터를 조회해 주는 도우미입니다. 아래 '서버 데이터'만 근거로 사용자의 질문에 답하세요.
                데이터에 없는 항목(로그·세션·네트워크 경로 등)을 물으면 rows는 비우고 note에 "현재 수집 대상이 아닙니다"라고 답하세요.

                [규칙]
                - 질문을 어떤 조건으로 해석했는지 parsed에 짧은 태그로 남기세요(예: "대상: 테라넷", "조건: 메모리 > 80%").
                - 표로 답하되 columns/rows의 칸 수를 맞추세요. 수치는 데이터 그대로 쓰세요. 반드시 JSON만 답하세요.

                [출력 형식]
                {"parsed": ["태그1", "태그2"], "columns": ["칼럼1", "칼럼2"], "rows": [["값1", "값2"]], "note": "N건 또는 안내"}

                [서버 데이터]
                %s

                [사용자 질문]
                %s""".formatted(gemini.toJson(fleet), text);

        JsonNode result;
        try {
            result = gemini.extractJson(gemini.generate(prompt), false);
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (Exception e) {
            return Map.of("ok", false, "reply", "질의 처리 실패: " + shortError(e));
        }
        return Map.of("ok", true, "result", result);
    }

    // ===== AI 알림 그룹핑 (여러 지표 알림을 '하나의 사건'으로 묶고 조합을 해석) =====
    // 관찰: CPU 하나가 올라도 CPU / CPU Core / CPU User 가 각각 임계를 넘어 알림이 여러 건 뜬다.
    // AiWACS의 탐지·판정은 그대로 두고(원본 알림 보존), AI는 관제자에게 '사건 단위'로 접어 보여준다.
    private static final Map<String, String> METRIC_KR2 = Map.of("cpu", "CPU", "memory", "메모리", "disk", "디스크");

    /** demo=true면 우리가 부하 실험에서 실제로 본 알림 세트를, false면 현재 판정 기반 실알림을 묶는다. */
    public Map<String, Object> groupAlarms(boolean demo) {
        if (!gemini.isConfigured()) {
            return Map.of("ok", false, "reply", NOT_CONFIGURED);
        }
        List<Map<String, Object>> alarms = demo ? demoAlarms() : currentAlarms();
        String source = demo ? "demo" : "current";
        if (alarms.isEmpty()) {
            return Map.of("ok", true, "source", source, "rawCount", 0,
                    "alarms", alarms, "result", Map.of("events", List.of()));
        }

        String prompt = """
                당신은 관제(NOC) 도우미입니다. 아래 '발생 알림'들을 관제자가 보기 쉽게 '사건(event)' 단위로 묶으세요.
                AiWACS의 알림 자체는 정확합니다. 당신은 탐지를 바꾸지 말고, 여러 줄을 하나로 묶어 원인만 해석합니다.

                [묶는 규칙]
                - 같은 서버에서 같은 자원군(예: CPU 계열: CPU/CPU Core/CPU User)의 알림은 '한 사건'으로 묶으세요.
                - 자원이 다르면(CPU vs 메모리 vs 디스크) 다른 사건입니다.
                - 어떤 세부 지표 조합이 떴는지로 원인 유형을 추정하세요:
                  · User·Core·전체가 함께 → 전 코어 계산 부하
                  · Core만 높고 전체는 낮음 → 단일 스레드 병목 가능성
                  · Wait만 높음 → CPU가 아니라 디스크/IO 대기 가능성
                  · System만 높음 → 커널/네트워크/컨텍스트 스위치 폭주 가능성
                - 원인은 단정하지 말고 "가능성"으로. 반드시 JSON만 답하세요.

                [출력 형식]
                {"events": [{"title": "사건 요약(예: server1 CPU 포화)", "server": "서버", "level": "주의|경고|위험", "count": 묶은 알림 수, "members": ["원본 알림 요약1", "원본 알림 요약2"], "cause": "조합으로 본 원인 추정", "action": "권장 조치"}]}

                [발생 알림]
                %s""".formatted(gemini.toJson(alarms));

        JsonNode result;
        try {
            result = gemini.extractJson(gemini.generate(prompt), false);
        } catch (GeminiClient.AiBusyException e) {
            return Map.of("ok", false, "reply", AI_BUSY);
        } catch (Exception e) {
            return Map.of("ok", false, "reply", "알림 그룹핑 실패: " + shortError(e));
        }
        return Map.of("ok", true, "source", source, "rawCount", alarms.size(),
                "alarms", alarms, "result", result);
    }

    /** 현재 판정 기준으로 주의·위험인 서버·지표를 알림 목록으로 (클론의 실데이터) */
    private List<Map<String, Object>> currentAlarms() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MonitoredServer s : servers.findAll()) {
            if (!servers.isOnline(s.getId())) {
                continue;
            }
            Map<String, Map<String, Object>> judged = servers.judge(s.getId()).orElse(null);
            if (judged == null) {
                continue;
            }
            judged.forEach((metric, m) -> {
                String status = String.valueOf(m.get("status"));
                if (!"정상".equals(status)) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("server", s.label());
                    a.put("company", s.getCompany());
                    a.put("metric", METRIC_KR2.getOrDefault(metric, metric));
                    a.put("level", status);
                    a.put("value", m.get("value"));
                    out.add(a);
                }
            });
        }
        return out;
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

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
