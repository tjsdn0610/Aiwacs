package com.sysone.aiwacs.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini REST API 호출 (generateContent).
 * API 키는 환경변수/.env의 GEMINI_API_KEY로만 받는다.
 */
@Component
public class GeminiClient {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final String apiKey;
    private final String model;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;

    public GeminiClient(@Value("${gemini.api-key:}") String apiKey,
                        @Value("${gemini.model}") String model,
                        JsonMapper jsonMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.jsonMapper = jsonMapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** AI 서버가 일시적으로 혼잡해 재시도 후에도 응답을 못 받은 경우 */
    public static class AiBusyException extends RuntimeException {
        public AiBusyException(Throwable cause) {
            super(cause);
        }
    }

    private static final int MAX_ATTEMPTS = 3;

    /**
     * 프롬프트를 보내고 응답 텍스트를 받는다.
     * 503(서버 혼잡)·429(요청 과다)는 일시적인 경우가 많아 1초, 2초 간격으로 재시도한다.
     */
    public String generate(String prompt) {
        for (int attempt = 1; ; attempt++) {
            try {
                return callOnce(prompt);
            } catch (HttpServerErrorException.ServiceUnavailable | HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new AiBusyException(e);
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AiBusyException(e);
                }
            }
        }
    }

    private String callOnce(String prompt) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        JsonNode resp = restClient.post()
                .uri("/models/{model}:generateContent", model)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        // candidates[0].content.parts[*].text 를 이어 붙임 (사고 과정(thought) 파트는 제외)
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : resp.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) {
                sb.append(part.path("text").asString(""));
            }
        }
        return sb.toString();
    }

    /**
     * AI 응답에서 JSON 부분만 골라 파싱한다 (설계 원칙: AI가 형식을 어겨도 안정적으로 처리).
     * 코드블록(```json)을 걷어내고, preferArray면 [ ~ ] 를 우선, 아니면 { ~ } 만 추출.
     */
    public JsonNode extractJson(String text, boolean preferArray) {
        String raw = text.strip().replace("```json", "").replace("```", "").strip();
        int s = -1, e = -1;
        if (preferArray) {
            s = raw.indexOf('[');
            e = raw.lastIndexOf(']');
        }
        if (s == -1) s = raw.indexOf('{');
        if (e == -1) e = raw.lastIndexOf('}');
        if (s != -1 && e != -1 && s <= e) {
            raw = raw.substring(s, e + 1);
        }
        return jsonMapper.readTree(raw);
    }

    public String toJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }
}
