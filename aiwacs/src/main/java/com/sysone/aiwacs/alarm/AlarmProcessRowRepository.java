package com.sysone.aiwacs.alarm;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmProcessRowRepository extends JpaRepository<AlarmProcessRow, Long> {

    List<AlarmProcessRow> findByAlarmIdInOrderByAtAsc(Collection<Long> alarmIds);
}
