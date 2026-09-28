package com.sysone.aiwacs.history;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface MetricHistoryRepository extends JpaRepository<MetricHistory, Long> {

    /** from 이상 to 미만 (하루치) */
    List<MetricHistory> findByServerIdAndTimeGreaterThanEqualAndTimeLessThanOrderByTimeAsc(Long serverId, Instant from, Instant to);

    /** 보관 기간이 지난 이력 삭제 */
    @Transactional
    @Modifying
    @Query("delete from MetricHistory h where h.time < :before")
    int deleteOlderThan(Instant before);
}
