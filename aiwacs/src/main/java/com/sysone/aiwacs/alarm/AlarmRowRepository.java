package com.sysone.aiwacs.alarm;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AlarmRowRepository extends JpaRepository<AlarmRow, Long> {

    /** 최근 알람 (앱을 다시 켤 때 메모리로 되살릴 범위) */
    List<AlarmRow> findAllByOrderByFirstAtDesc(Pageable page);

    @Query("select max(a.id) from AlarmRow a")
    Long maxId();

    /** 과거 비슷한 이벤트 찾기: 같은 서버·자원, 지금 이벤트보다 앞선 알람 */
    List<AlarmRow> findByServerIdAndResourceAndFirstAtBeforeOrderByFirstAtDesc(Long serverId, String resource, Instant before);
}
