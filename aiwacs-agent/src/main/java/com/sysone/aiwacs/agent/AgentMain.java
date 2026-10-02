package com.sysone.aiwacs.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AiWACS Agent.
 * 설정한 주기마다 이 서버의 지표를 수집해 AiWACS의 /api/agent/metrics 로 보낸다.
 * 연결이 끊겨도 멈추지 않고 계속 재시도한다.
 */
public class AgentMain {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws InterruptedException {
        Collector collector = new Collector();
        AgentConfig cfg = AgentConfig.load(collector.hostname());
        if (cfg.serverUrl().isBlank()) {
            System.err.println("AiWACS 주소가 설정되지 않았습니다.");
            System.err.println("agent.properties에 server.url=http://<AiWACS IP>:8080 을 적거나");
            System.err.println("환경변수 AIWACS_SERVER_URL 을 설정해 주세요.");
            System.exit(1);
        }

        String endpoint = cfg.serverUrl() + "/api/agent/metrics";
        log("AiWACS Agent 시작 — 서버 이름: " + cfg.serverName() + ", 전송 대상: " + endpoint
                + ", 주기: " + cfg.intervalSec() + "초");

        log("조치 실행(프로세스 종료·우선순위 낮추기): " + (cfg.actionEnabled()
                ? "허용 — AiWACS 화면에서 사람이 승인한 조치만 실행합니다"
                : "꺼짐 (켜려면 agent.properties에 action.enabled=true)"));

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ObjectMapper json = new ObjectMapper();
        Boolean lastOk = null; // 상태가 바뀔 때만 로그를 남겨 화면이 도배되지 않게 함

        // 조치는 별도 스레드 하나에서 차례로 실행 (종료 확인에 몇 초 걸려도 지표 전송이 멈추지 않게)
        ActionExecutor executor = new ActionExecutor();
        ExecutorService actionThread = Executors.newSingleThreadExecutor();
        Queue<Map<String, Object>> results = new ConcurrentLinkedQueue<>();

        while (true) {
            List<Map<String, Object>> sending = new ArrayList<>();
            try {
                Map<String, Object> data = collector.collect();
                if (data != null) {
                    data.put("serverName", cfg.serverName());
                    data.put("hostname", collector.hostname());
                    data.put("os", collector.osName());
                    data.put("actionEnabled", cfg.actionEnabled());
                    // 지난번 이후 끝난 조치 결과를 이번 전송에 함께 싣는다
                    for (Map<String, Object> r; (r = results.poll()) != null; ) {
                        sending.add(r);
                    }
                    data.put("actionResults", sending);

                    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
                    if (!cfg.token().isBlank()) {
                        req.header("X-Agent-Token", cfg.token());
                    }
                    HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());

                    boolean ok = resp.statusCode() == 200;
                    if (!Boolean.valueOf(ok).equals(lastOk)) {
                        log(ok ? "AiWACS 전송 성공 (이후 정상 전송 중에는 로그를 남기지 않습니다)"
                                : "AiWACS 전송 실패: HTTP " + resp.statusCode() + " " + resp.body());
                    }
                    lastOk = ok;
                    if (ok) {
                        sending.clear();
                        receiveCommands(json, resp.body(), executor, cfg.actionEnabled(), actionThread, results);
                    }
                }
            } catch (Exception e) {
                if (!Boolean.FALSE.equals(lastOk)) {
                    log("AiWACS 연결 실패: " + e.getClass().getSimpleName() + " " + e.getMessage()
                            + " — 계속 재시도합니다.");
                }
                lastOk = false;
            }
            results.addAll(sending); // 전송에 실패한 조치 결과는 다음 전송 때 다시 보낸다
            Thread.sleep(cfg.intervalSec() * 1000L);
        }
    }

    /** 지표 전송 응답에 실려 온 조치 명령을 실행 대기열에 넣는다 (AiWACS → Agent 방향의 유일한 통로) */
    private static void receiveCommands(ObjectMapper json, String body, ActionExecutor executor, boolean enabled,
                                        ExecutorService actionThread, Queue<Map<String, Object>> results) {
        try {
            JsonNode cmds = json.readTree(body).path("commands");
            for (JsonNode c : cmds) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cmd = json.convertValue(c, Map.class);
                log("조치 명령 수신: " + cmd.get("action") + " " + cmd.get("name") + "(PID " + cmd.get("pid")
                        + "), 승인자 " + cmd.get("by"));
                actionThread.submit(() -> {
                    Map<String, Object> r = executor.execute(cmd, enabled);
                    log("조치 결과: " + r.get("message"));
                    results.add(r);
                });
            }
        } catch (Exception e) {
            log("조치 명령을 읽지 못했습니다: " + e.getMessage());
        }
    }

    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg);
    }
}
