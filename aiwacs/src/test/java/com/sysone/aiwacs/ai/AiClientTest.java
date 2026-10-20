package com.sysone.aiwacs.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI 응답 JSON 읽기 — 작은 모델이 형식을 조금 어겨도 읽혀야 한다 */
class AiClientTest {

    private final AiClient client = new AiClient(JsonMapper.builder().build()) {
        @Override public boolean isConfigured() { return true; }
        @Override public String generate(String prompt) { return ""; }
    };

    @Test
    void 작은따옴표와_끝쉼표도_읽는다() {
        JsonNode n = client.extractJson("결과입니다 ```json\n{'events': [{'key': 'a', 'cause': '웹서버 \"지연\"'},]}\n```", false);
        assertEquals("a", n.path("events").get(0).path("key").asString());
    }

    @Test
    void 객체_대신_목록만_와도_목록으로_읽는다() {
        // 로컬 모델이 {"events": [...]} 대신 [...]만 보낸 실제 사례 — 앞뒤를 { } 로 자르면 형식 오류가 난다
        String raw = "```json [ {\"key\": \"2|cpu\", \"cause\": \"a\"}, {\"key\": \"98|cpu\", \"cause\": \"b\"} ] ```";
        JsonNode n = client.extractJson(raw, true);
        assertEquals(2, n.size());
        assertEquals("98|cpu", n.get(1).path("key").asString());
    }

    @Test
    void 정상_JSON은_그대로() {
        JsonNode n = client.extractJson("[{\"title\":\"x\"}]", true);
        assertEquals("x", n.get(0).path("title").asString());
    }
}
