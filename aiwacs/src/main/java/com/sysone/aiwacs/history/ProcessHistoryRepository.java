package com.sysone.aiwacs.history;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessHistoryRepository extends JpaRepository<ProcessHistory, Long> {

    List<ProcessHistory> findByServerIdAndTimeGreaterThanEqualOrderByTimeAsc(Long serverId, Instant from);

    /** 보관 기간이 지난 이력 삭제 */
    @Transactional
    @Modifying
    @Query("delete from ProcessHistory h where h.time < :before")
    int deleteOlderThan(Instant before);
}
