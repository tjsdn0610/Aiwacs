package com.sysone.aiwacs.history;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 서버별 1분 평균 지표 한 줄. Resource Map 그래프의 이력으로 쓴다. */
@Entity
@Table(name = "metric_history", indexes = @Index(columnList = "serverId, time"))
public class MetricHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long serverId;

    /** 그 1분의 시작 시각 (예: 10:31:00 → 10:31:00~10:31:59의 평균) */
    @Column(nullable = false)
    private Instant time;

    private double cpu;
    private double memory;
    private double disk;

    /** 송신+수신 합계 (KB/s) */
    private double traffic;

    protected MetricHistory() {
    }

    public MetricHistory(Long serverId, Instant time, double cpu, double memory, double disk, double traffic) {
        this.serverId = serverId;
        this.time = time;
        this.cpu = cpu;
        this.memory = memory;
        this.disk = disk;
        this.traffic = traffic;
    }

    public Long getServerId() {
        return serverId;
    }

    public Instant getTime() {
        return time;
    }

    public double getCpu() {
        return cpu;
    }

    public double getMemory() {
        return memory;
    }

    public double getDisk() {
        return disk;
    }

    public double getTraffic() {
        return traffic;
    }
}
