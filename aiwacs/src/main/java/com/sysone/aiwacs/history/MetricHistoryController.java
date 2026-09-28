package com.sysone.aiwacs.history;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Resource Map 그래프용 지표 이력 API */
@RestController
public class MetricHistoryController {

    private final MetricHistoryService service;

    public MetricHistoryController(MetricHistoryService service) {
        this.service = service;
    }

    /** 최근 minutes분의 1분 평균 이력. 예) /api/history?serverId=1&minutes=60 */
    @GetMapping("/api/history")
    public List<Map<String, Object>> history(@RequestParam Long serverId,
                                             @RequestParam(defaultValue = "60") int minutes) {
        return service.history(serverId, minutes);
    }

    /** 실시간 그래프용 최근 약 80초의 원본 값. 화면을 새로 열거나 서버를 바꿀 때 그래프를 바로 채운다. */
    @GetMapping("/api/history/recent")
    public List<Map<String, Object>> recent(@RequestParam Long serverId) {
        return service.recent(serverId);
    }
}
