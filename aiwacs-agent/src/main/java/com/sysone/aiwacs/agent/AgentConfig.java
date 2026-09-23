package com.sysone.aiwacs.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Agent 설정.
 * 실행 폴더의 agent.properties 파일을 읽고, 같은 항목의 환경변수가 있으면 환경변수를 우선한다.
 *
 * <pre>
 * server.url   (AIWACS_SERVER_URL)   AiWACS 주소. 예: http://192.168.123.7:8080  [필수]
 * server.name  (AIWACS_SERVER_NAME)  화면에 표시할 서버 이름. 기본값: 호스트명
 * agent.token  (AIWACS_AGENT_TOKEN)  AiWACS에 설정한 토큰과 같은 값. 비워두면 토큰 없이 전송
 * interval.sec (AIWACS_INTERVAL_SEC) 전송 주기(초). 기본값: 2
 * </pre>
 */
public record AgentConfig(String serverUrl, String serverName, String token, int intervalSec) {

    public static AgentConfig load(String defaultName) {
        Properties props = new Properties();
        Path file = Path.of("agent.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("[경고] agent.properties를 읽지 못했습니다: " + e.getMessage());
            }
        }

        String url = get(props, "server.url", "AIWACS_SERVER_URL", "");
        String name = get(props, "server.name", "AIWACS_SERVER_NAME", defaultName);
        String token = get(props, "agent.token", "AIWACS_AGENT_TOKEN", "");
        int interval;
        try {
            interval = Math.max(1, Integer.parseInt(get(props, "interval.sec", "AIWACS_INTERVAL_SEC", "2")));
        } catch (NumberFormatException e) {
            interval = 2;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new AgentConfig(url, name, token, interval);
    }

    private static String get(Properties props, String key, String envKey, String def) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env.strip();
        }
        String v = props.getProperty(key);
        return v != null && !v.isBlank() ? v.strip() : def;
    }
}
