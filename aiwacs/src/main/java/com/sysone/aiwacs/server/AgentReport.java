package com.sysone.aiwacs.server;

import java.util.List;
import java.util.Map;

/**
 * Agent가 주기적으로 보내는 지표 한 묶음.
 * core·procs·partitions·traffic은 대시보드용, detail·io·topIo는 AI 진단용.
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
        List<Map<String, Object>> topIo) {

    public record Proc(String name, double cpu, double mem) {}
}
