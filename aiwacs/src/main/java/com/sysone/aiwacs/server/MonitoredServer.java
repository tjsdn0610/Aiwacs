package com.sysone.aiwacs.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 모니터링 대상 서버. Agent가 처음 지표를 보내면 자동으로 등록된다. */
@Entity
@Table(name = "monitored_server")
public class MonitoredServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Agent 설정의 server.name (서버를 구분하는 이름) */
    @Column(nullable = false, unique = true)
    private String name;

    private String hostname;
    private String ip;
    private String os;

    /** 화면에 보여줄 이름 (null이면 Agent가 보낸 name 사용) */
    private String displayName;

    /** 이 서버가 속한 고객사 (null이면 미지정) */
    private String company;

    /** 이 서버에 적용할 알림 정책 id (null이면 기본 정책) */
    private Long policyId;

    protected MonitoredServer() {
    }

    public MonitoredServer(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    /** 화면 표시용 이름: 표시 이름이 있으면 그것, 없으면 Agent ID */
    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName : name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public void setPolicyId(Long policyId) {
        this.policyId = policyId;
    }
}
