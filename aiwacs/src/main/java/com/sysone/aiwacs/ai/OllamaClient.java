package com.sysone.aiwacs.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 로컬 LLM(Ollama) 호출 (/api/chat).
 * 서버 지표·프로세스 정보가 외부로 나가지 않도록 같은 기기(또는 사내망)에서 도는 모델을 사용한다.
 */
@Component
public class OllamaClient extends AiClient {

    private final String model;
    private final RestClient restClient;

    public OllamaClient(@Value("${ollama.base-url}") String baseUrl,
                        @Value("${ollama.model}") String model,
                        JsonMapper jsonMapper) {
        super(jsonMapper);
        this.model = model;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        // 로컬 모델은 클라우드보다 느리고, 첫 호출 때 모델을 메모리에 올리는 시간도 걸린다
        factory.setReadTimeout(Duration.ofSeconds(180));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return model != null && !model.isBlank();
    }

    @Override
    public String generate(String prompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", false,
                // 판정·JSON 변환은 일관성이 중요하므로 무작위성을 낮춘다
                "options", Map.of("temperature", 0.2));
        try {
            JsonNode resp = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return resp.path("message").path("content").asString("");
        } catch (ResourceAccessException e) {
            // 연결 거부·시간 초과 = Ollama가 꺼져 있거나 응답이 너무 늦음
            throw new AiUnavailableException(e);
        }
    }
}
