package com.sysone.aiwacs.policy;

import jakarta.persistence.Embeddable;

/** 한 지표의 임계치 한 쌍 (주의 / 위험). JSON으로는 {"warn": 70, "danger": 90} 형태. */
@Embeddable
public class Threshold {

    private Integer warn;
    private Integer danger;

    public Threshold() {
    }

    public Threshold(Integer warn, Integer danger) {
        this.warn = warn;
        this.danger = danger;
    }

    public Integer getWarn() {
        return warn;
    }

    public void setWarn(Integer warn) {
        this.warn = warn;
    }

    public Integer getDanger() {
        return danger;
    }

    public void setDanger(Integer danger) {
        this.danger = danger;
    }
}
