package com.sysone.aiwacs.ai;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

/**
 * AI 처리내역 초안 기능이 승인·저장한 대응 기록을 보관한다.
 * 프로토타입 단계라 메모리에만 둔다(재시작 시 초기화). 실제로는 DB 테이블로 쌓아
 * "유사 장애 조치 추천"에 재활용하는 지식 베이스가 된다.
 */
@Component
public class HandlingNoteStore {

    public record Note(Long serverId, String server, String content, String createdAt) {}

    private static final int MAX = 200;
    private final ConcurrentLinkedDeque<Note> notes = new ConcurrentLinkedDeque<>();

    public Note add(Long serverId, String server, String content) {
        String now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString().replace('T', ' ');
        Note n = new Note(serverId, server, content, now);
        notes.addFirst(n);
        while (notes.size() > MAX) {
            notes.removeLast();
        }
        return n;
    }

    /** 최신순 최근 기록 */
    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Note n : notes) {
            if (out.size() >= limit) {
                break;
            }
            out.add(Map.of("server", n.server(), "content", n.content(), "createdAt", n.createdAt()));
        }
        return out;
    }
}
