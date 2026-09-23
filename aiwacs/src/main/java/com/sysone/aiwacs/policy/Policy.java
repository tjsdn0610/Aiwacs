package com.sysone.aiwacs.policy;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 알림 정책 (고객사 + 정책명 + CPU/메모리/디스크 임계치).
 * DB에는 한 줄(cpu_warn, cpu_danger, ...)로 저장되고,
 * JSON으로는 화면이 쓰기 편한 {"cpu": {"warn", "danger"}, ...} 형태로 나간다.
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
            @AttributeOverride(name = "warn", column = @Column(name = "cpu_warn")),
            @AttributeOverride(name = "danger", column = @Column(name = "cpu_danger"))
    })
    private Threshold cpu;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "warn", column = @Column(name = "memory_warn")),
            @AttributeOverride(name = "danger", column = @Column(name = "memory_danger"))
    })
    private Threshold memory;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "warn", column = @Column(name = "disk_warn")),
            @AttributeOverride(name = "danger", column = @Column(name = "disk_danger"))
    })
    private Threshold disk;

    protected Policy() {
    }

    public Policy(String company, String name, Threshold cpu, Threshold memory, Threshold disk) {
        this.company = company;
        this.name = name;
        this.cpu = cpu;
        this.memory = memory;
        this.disk = disk;
    }

    /** "cpu" / "memory" / "disk" 이름으로 임계치 조회 (없는 지표면 null) */
    public Threshold threshold(String metric) {
        return switch (metric) {
            case "cpu" -> cpu;
            case "memory" -> memory;
            case "disk" -> disk;
            default -> null;
        };
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
}
