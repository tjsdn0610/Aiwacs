package com.sysone.aiwacs.server;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ServerRepository extends JpaRepository<MonitoredServer, Long> {

    Optional<MonitoredServer> findByName(String name);

    List<MonitoredServer> findAllByOrderByIdAsc();

    /** 정책이 삭제되면 그 정책을 쓰던 서버를 기본 정책으로 되돌린다 */
    @Modifying
    @Query("update MonitoredServer s set s.policyId = null where s.policyId = :policyId")
    int clearPolicy(Long policyId);
}
