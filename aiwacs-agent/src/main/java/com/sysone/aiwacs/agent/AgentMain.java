package com.sysone.aiwacs.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

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

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ObjectMapper json = new ObjectMapper();
        Boolean lastOk = null; // 상태가 바뀔 때만 로그를 남겨 화면이 도배되지 않게 함

        while (true) {
            try {
                Map<String, Object> data = collector.collect();
                if (data != null) {
                    data.put("serverName", cfg.serverName());
                    data.put("hostname", collector.hostname());
                    data.put("os", collector.osName());

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
                }
            } catch (Exception e) {
                if (!Boolean.FALSE.equals(lastOk)) {
                    log("AiWACS 연결 실패: " + e.getClass().getSimpleName() + " " + e.getMessage()
                            + " — 계속 재시도합니다.");
                }
                lastOk = false;
            }
            Thread.sleep(cfg.intervalSec() * 1000L);
        }
    }

    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg);
    }
}
