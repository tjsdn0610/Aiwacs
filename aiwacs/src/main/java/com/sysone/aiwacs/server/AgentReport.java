package com.sysone.aiwacs.server;

import java.util.List;
import java.util.Map;

/**
 * Agent가 주기적으로 보내는 지표 한 묶음.
 * core·procs·partitions·traffic은 대시보드용, detail·io·topIo는 AI 진단용.
 * actionEnabled·actionResults는 조치 실행용 (이 서버 Agent가 조치를 허용하는지, 지난 전송 이후 끝난 조치 결과).
 */
public record AgentReport(
        String serverName,
        String hostname,
        String os,
        Map<String, Double> core,
        List<Proc> procs,
        List<Map<String, Object>> partitions,
        Map<String, Double> traffic,
        Map<String, Object> detail,
        Map<String, Object> io,
        List<Map<String, Object>> topIo,
        Boolean actionEnabled,
        List<Map<String, Object>> actionResults) {

    /**
     * 프로세스 하나. pid·start(시작 시각, epoch ms)는 조치할 때 "같은 프로세스"인지 확인하는 열쇠.
     * (예전 Agent는 pid·start·user를 보내지 않아 null)
     */
    public record Proc(String name, double cpu, double mem, Long pid, Long start, String user) {}
}
