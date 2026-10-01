package com.sysone.aiwacs.policy;

import jakarta.persistence.Embeddable;

/**
 * 한 지표의 알림 레벨 임계치 (AiWACS와 같은 4단계: 주의 / 경고 / 위험 / 장애).
 * JSON으로는 {"caution": 70, "warning": 80, "danger": 90, "critical": 95} 형태.
 */
@Embeddable
public class Threshold {

    private Integer caution;   // 주의
    private Integer warning;   // 경고
    private Integer danger;    // 위험
    private Integer critical;  // 장애

    public Threshold() {
    }

    public Threshold(Integer caution, Integer warning, Integer danger, Integer critical) {
        this.caution = caution;
        this.warning = warning;
        this.danger = danger;
        this.critical = critical;
    }

    /** 레벨 키(caution/warning/danger/critical)로 값 조회 */
    public Integer get(String level) {
        return switch (level) {
            case "caution" -> caution;
            case "warning" -> warning;
            case "danger" -> danger;
            case "critical" -> critical;
            default -> null;
        };
    }

    /** 한 레벨만 바꾼 새 임계치 (Embeddable은 새 객체로 교체해야 변경이 확실히 반영된다) */
    public Threshold with(String level, int value) {
        return new Threshold(
                "caution".equals(level) ? value : caution,
                "warning".equals(level) ? value : warning,
                "danger".equals(level) ? value : danger,
                "critical".equals(level) ? value : critical);
    }

    /** 네 레벨이 모두 비어 있음 = 이 지표는 정책에서 사용하지 않음 (AiWACS 정책 화면의 체크 해제) */
    public boolean isEmpty() {
        return caution == null && warning == null && danger == null && critical == null;
    }

    /** 사용 중인 지표라면: 네 레벨 모두 0~100 + 주의 ≤ 경고 ≤ 위험 ≤ 장애 */
    public boolean isValid() {
        if (isEmpty()) {
            return true;
        }
        for (Integer v : new Integer[] {caution, warning, danger, critical}) {
            if (v == null || v < 0 || v > 100) {
                return false;
            }
        }
        return isOrdered();
    }

    /** 주의 ≤ 경고 ≤ 위험 ≤ 장애 순서인지 (비어 있는 레벨은 건너뜀) */
    public boolean isOrdered() {
        Integer prev = null;
        for (Integer v : new Integer[] {caution, warning, danger, critical}) {
            if (v == null) {
                continue;
            }
            if (prev != null && v < prev) {
                return false;
            }
            prev = v;
        }
        return true;
    }

    public Integer getCaution() {
        return caution;
    }

    public void setCaution(Integer caution) {
        this.caution = caution;
    }

    public Integer getWarning() {
        return warning;
    }

    public void setWarning(Integer warning) {
        this.warning = warning;
    }

    public Integer getDanger() {
        return danger;
    }

    public void setDanger(Integer danger) {
        this.danger = danger;
    }

    public Integer getCritical() {
        return critical;
    }

    public void setCritical(Integer critical) {
        this.critical = critical;
    }
}
