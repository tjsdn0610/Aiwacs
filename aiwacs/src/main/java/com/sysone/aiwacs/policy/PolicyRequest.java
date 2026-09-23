package com.sysone.aiwacs.policy;

/** 정책 추가/수정 요청 본문. 비어 있는 항목은 추가 시 기본값, 수정 시 기존값을 유지한다. */
public record PolicyRequest(String company, String name, Threshold cpu, Threshold memory, Threshold disk) {
}
