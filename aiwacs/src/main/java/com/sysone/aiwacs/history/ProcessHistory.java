package com.sysone.aiwacs.history;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 서버별 1분 동안의 프로세스 한 줄 (그 1분의 CPU 평균·메모리 최대).
 * 실제 AiWACS도 프로세스별 지표를 1분 단위로 저장한다(tbl_process_1min).
 * AI 진단이 "부하가 끝난 뒤에도" 그 시간에 어떤 프로세스가 올라갔는지 볼 수 있게 하는 이력.
 */
@Entity
@Table(name = "process_history", indexes = @Index(columnList = "serverId, time"))
public class ProcessHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long serverId;

    /** 그 1분의 시작 시각 */
    @Column(nullable = false)
    private Instant time;

    @Column(nullable = false, length = 40)
    private String name;

    private Long pid;

    /** 프로세스 시작 시각 (epoch ms, 예전 Agent면 null) — "구간 중에 새로 시작했는지" 판단용 */
    private Long started;

    /** 1분 평균 CPU(%) — 그 1분 동안 목록에 없던 순간은 0으로 쳐서 평균 */
    private double cpu;

    /** 1분 중 최대 메모리(%) */
    private double mem;

    protected ProcessHistory() {
    }

    public ProcessHistory(Long serverId, Instant time, String name, Long pid, Long started, double cpu, double mem) {
        this.serverId = serverId;
        this.time = time;
        this.name = name;
        this.pid = pid;
        this.started = started;
        this.cpu = cpu;
        this.mem = mem;
    }

    public Long getServerId() { return serverId; }
    public Instant getTime() { return time; }
    public String getName() { return name; }
    public Long getPid() { return pid; }
    public Long getStarted() { return started; }
    public double getCpu() { return cpu; }
    public double getMem() { return mem; }
}
