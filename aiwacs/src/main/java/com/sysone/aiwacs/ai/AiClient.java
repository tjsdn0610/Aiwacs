package com.sysone.aiwacs.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI(LLM) 호출 공통 부분. 실제 호출 방식(Gemini / 로컬 LLM)은 하위 클래스가 정한다.
 * AiService는 이 타입만 알기 때문에, 어떤 AI를 쓸지는 @Component를 붙인 쪽 하나로 결정된다.
 */
public abstract class AiClient {

    protected final JsonMapper jsonMapper;

    protected AiClient(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** 호출에 필요한 설정(API 키, 주소 등)이 갖춰졌는지 */
    public abstract boolean isConfigured();

    /** 프롬프트를 보내고 응답 텍스트를 받는다. */
    public abstract String generate(String prompt);

    /** AI 서버가 일시적으로 혼잡해 재시도 후에도 응답을 못 받은 경우 */
    public static class AiBusyException extends RuntimeException {
        public AiBusyException(Throwable cause) {
            super(cause);
        }
    }

    /** AI 서버에 아예 연결할 수 없는 경우 (예: 로컬 LLM이 꺼져 있음) */
    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(Throwable cause) {
            super(cause);
        }
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
