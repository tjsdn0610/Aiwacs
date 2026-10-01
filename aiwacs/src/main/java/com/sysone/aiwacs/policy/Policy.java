package com.sysone.aiwacs.policy;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 알림 정책 (고객사 + 정책명 + CPU/메모리/디스크 임계치, 레벨은 AiWACS와 같은 주의/경고/위험/장애 4단계).
 * DB에는 한 줄(cpu_warn, cpu_warning, cpu_danger, cpu_critical, ...)로 저장되고,
 * JSON으로는 화면이 쓰기 편한 {"cpu": {"caution", "warning", "danger", "critical"}, ...} 형태로 나간다.
 * ※ 주의(caution)의 컬럼명이 *_warn인 것은 2단계(주의/위험) 시절 저장된 값을 그대로 이어 쓰기 위해서다.
 */
@Entity
@Table(name = "alert_policy")
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String company;

    @Column(nullable = false)
    private String name;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "caution", column = @Column(name = "cpu_warn")),
            @AttributeOverride(name = "warning", column = @Column(name = "cpu_warning")),
            @AttributeOverride(name = "danger", column = @Column(name = "cpu_danger")),
            @AttributeOverride(name = "critical", column = @Column(name = "cpu_critical"))
    })
    private Threshold cpu;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "caution", column = @Column(name = "memory_warn")),
            @AttributeOverride(name = "warning", column = @Column(name = "memory_warning")),
            @AttributeOverride(name = "danger", column = @Column(name = "memory_danger")),
            @AttributeOverride(name = "critical", column = @Column(name = "memory_critical"))
    })
    private Threshold memory;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "caution", column = @Column(name = "disk_warn")),
            @AttributeOverride(name = "warning", column = @Column(name = "disk_warning")),
            @AttributeOverride(name = "danger", column = @Column(name = "disk_danger")),
            @AttributeOverride(name = "critical", column = @Column(name = "disk_critical"))
    })
    private Threshold disk;

    /** 처음 등록된 시각 (등록 일자 기록 전에 만들어진 정책은 비어 있음) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /** 마지막으로 저장(추가·수정·AI 임계치 변경)된 시각 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    protected Policy() {
    }

    public Policy(String company, String name, Threshold cpu, Threshold memory, Threshold disk) {
        this.company = company;
        this.name = name;
        this.cpu = cpu;
        this.memory = memory;
        this.disk = disk;
    }

    @PrePersist
    void created() {
        createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        touch();
    }

    /** 저장될 때마다 수정일자를 자동으로 기록 (값이 실제로 바뀐 경우에만 호출됨) */
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * "cpu" / "memory" / "disk" 이름으로 임계치 조회.
     * 없는 지표이거나, 정책에서 그 지표를 사용하지 않도록 꺼 두었으면 null.
     */
    public Threshold threshold(String metric) {
        Threshold th = switch (metric) {
            case "cpu" -> cpu;
            case "memory" -> memory;
            case "disk" -> disk;
            default -> null;
        };
        return th == null || th.isEmpty() ? null : th;
    }

    public Long getId() {
        return id;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Threshold getCpu() {
        return cpu;
    }

    public void setCpu(Threshold cpu) {
        this.cpu = cpu;
    }

    public Threshold getMemory() {
        return memory;
    }

    public void setMemory(Threshold memory) {
        this.memory = memory;
    }

    public Threshold getDisk() {
        return disk;
    }

    public void setDisk(Threshold disk) {
        this.disk = disk;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
