package com.sysone.aiwacs.policy;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    List<Policy> findAllByOrderByIdAsc();

    Optional<Policy> findFirstByOrderByIdAsc();
}
