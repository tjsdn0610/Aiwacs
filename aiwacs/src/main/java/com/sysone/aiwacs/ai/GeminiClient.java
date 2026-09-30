package com.sysone.aiwacs.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * [현재 사용 안 함] 보안상 서버 지표가 외부로 나가지 않도록 로컬 LLM(OllamaClient)으로 전환했다.
 * 다시 Gemini를 쓰려면 아래 @Component 주석을 풀고, OllamaClient의 @Component를 주석 처리한 뒤
 * application.properties의 Gemini 설정 주석을 푼다.
 */
// @Component
public class GeminiClient extends AiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final String apiKey;
    private final String model;
    private final String fallbackModel;
    private final RestClient restClient;

    public GeminiClient(@Value("${gemini.api-key:}") String apiKey,
                        @Value("${gemini.model}") String model,
                        @Value("${gemini.fallback-model:}") String fallbackModel,
                        JsonMapper jsonMapper) {
        super(jsonMapper);
        this.apiKey = apiKey;
        this.model = model;
        this.fallbackModel = fallbackModel;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static final int MAX_ATTEMPTS = 3;

    /**
     * 프롬프트를 보내고 응답 텍스트를 받는다.
     * 기본 모델이 혼잡하거나 사용 한도를 넘으면 예비 모델(gemini.fallback-model)로 한 번 더 시도한다.
     */
    @Override
    public String generate(String prompt) {
        try {
            return generateWith(model, prompt);
        } catch (AiBusyException e) {
            if (fallbackModel == null || fallbackModel.isBlank() || fallbackModel.equals(model)) {
                throw e;
            }
            log.warn("Gemini 기본 모델({}) 혼잡·한도 초과 → 예비 모델({})로 전환", model, fallbackModel);
            return generateWith(fallbackModel, prompt);
        }
    }

    /**
     * 503(서버 혼잡)은 일시적인 경우가 많아 1초, 2초 간격으로 재시도한다.
     */
    private String generateWith(String modelName, String prompt) {
        for (int attempt = 1; ; attempt++) {
            try {
                return callOnce(modelName, prompt);
            } catch (HttpServerErrorException.ServiceUnavailable | HttpClientErrorException.TooManyRequests e) {
                log.warn("Gemini {} 응답 {} (시도 {}/{}): {}", modelName, e.getStatusCode().value(),
                        attempt, MAX_ATTEMPTS, e.getResponseBodyAsString().replaceAll("\\s+", " "));
                // 429(사용 한도 초과)는 몇 초 기다려도 풀리지 않으므로 재시도 없이 바로 넘긴다
                if (attempt >= MAX_ATTEMPTS || e instanceof HttpClientErrorException.TooManyRequests) {
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

    private String callOnce(String modelName, String prompt) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        JsonNode resp = restClient.post()
                .uri("/models/{model}:generateContent", modelName)
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
}
