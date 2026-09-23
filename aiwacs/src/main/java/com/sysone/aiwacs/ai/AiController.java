package com.sysone.aiwacs.ai;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 운영 도우미 API (진단 / 자연어 임계치 설정) */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/threshold")
    public Map<String, Object> threshold(@RequestBody Map<String, Object> body) {
        Object msg = body.getOrDefault("message", "");
        return aiService.changeThreshold(String.valueOf(msg));
    }

    @PostMapping("/diagnose")
    public Map<String, Object> diagnose() {
        return aiService.diagnose();
    }
}
