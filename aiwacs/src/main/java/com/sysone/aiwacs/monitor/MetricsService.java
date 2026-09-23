package com.sysone.aiwacs.monitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.VirtualMemory;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * 시스템 지표 수집 (OSHI).
 * 판정은 하지 않고 "측정값"만 돌려준다. 판정은 PolicyService가 담당.
 */
@Service
public class MetricsService {

    private static final double MB = 1024.0 * 1024.0;

    private final HardwareAbstractionLayer hal;
    private final OperatingSystem os;
    private final CentralProcessor cpu;
    private final GlobalMemory memory;

    // 프로세스 CPU 사용률은 "직전 측정과의 차이"로 계산하므로 이전 스냅샷을 보관
    private Map<Integer, OSProcess> lastProcs = new HashMap<>();

    // 트래픽 속도 계산용 이전 값
    private List<NetworkIF> netIfs;
    private long lastSent = -1;
    private long lastRecv = -1;
    private long lastNetTime;

    public MetricsService() {
        SystemInfo si = new SystemInfo();
        this.hal = si.getHardware();
        this.os = si.getOperatingSystem();
        this.cpu = hal.getProcessor();
        this.memory = hal.getMemory();
    }

    /** CPU/메모리/디스크 현재 사용률(%) — 판정 대상 3개 지표 */
    public Map<String, Double> coreMetrics() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("cpu", cpuPercent());
        m.put("memory", memoryPercent());
        m.put("disk", rootDiskPercent());
        return m;
    }

    /** 0.5초 동안 측정한 CPU 사용률 */
    public double cpuPercent() {
        long[] prev = cpu.getSystemCpuLoadTicks();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return cpu.getSystemCpuLoadBetweenTicks(prev) * 100;
    }

    public double memoryPercent() {
        long total = memory.getTotal();
        return (total - memory.getAvailable()) * 100.0 / total;
    }

    public double rootDiskPercent() {
        return os.getFileSystem().getFileStores(true).stream()
                .filter(fs -> "/".equals(fs.getMount()))
                .findFirst()
                .map(MetricsService::usagePercent)
                .orElse(0.0);
    }

    /** 사용률 = used / (used + 사용자가 쓸 수 있는 여유공간) — df 명령과 같은 계산 */
    private static double usagePercent(OSFileStore fs) {
        long used = fs.getTotalSpace() - fs.getFreeSpace();
        long denom = used + fs.getUsableSpace();
        return denom > 0 ? used * 100.0 / denom : 0.0;
    }

    /** 전체 프로세스의 CPU/메모리 사용률 (CPU는 직전 호출 이후 기간 기준) */
    public synchronized List<ProcInfo> processes() {
        long totalMem = memory.getTotal();
        List<OSProcess> current = os.getProcesses();
        Map<Integer, OSProcess> snapshot = new HashMap<>();
        List<ProcInfo> result = new ArrayList<>();
        for (OSProcess p : current) {
            snapshot.put(p.getProcessID(), p);
            double cpuLoad = p.getProcessCpuLoadBetweenTicks(lastProcs.get(p.getProcessID())) * 100;
            double mem = p.getResidentMemory() * 100.0 / totalMem;
            String name = p.getName() == null || p.getName().isBlank() ? "unknown" : p.getName();
            result.add(new ProcInfo(name.length() > 20 ? name.substring(0, 20) : name, round1(cpuLoad), round1(mem)));
        }
        lastProcs = snapshot;
        return result;
    }

    public record ProcInfo(String name, double cpu, double mem) {}

    /** 파티션별 디스크 사용률 (사용률 높은 순 5개) */
    public List<Map<String, Object>> partitions() {
        return os.getFileSystem().getFileStores(true).stream()
                .filter(fs -> fs.getTotalSpace() > 0)
                .sorted(Comparator.comparingDouble(MetricsService::usagePercent).reversed())
                .limit(5)
                .map(fs -> Map.<String, Object>of("name", fs.getMount(), "percent", round1(usagePercent(fs))))
                .toList();
    }

    /** 송수신 속도 (KB/s) — 직전 호출 이후 경과 시간으로 나눠 초당 값으로 계산 */
    public synchronized Map<String, Double> traffic() {
        if (netIfs == null) {
            netIfs = hal.getNetworkIFs();
        }
        long sent = 0, recv = 0;
        for (NetworkIF nif : netIfs) {
            nif.updateAttributes();
            sent += nif.getBytesSent();
            recv += nif.getBytesRecv();
        }
        long now = System.currentTimeMillis();
        double sentRate = 0, recvRate = 0;
        if (lastSent >= 0) {
            double sec = Math.max(0.001, (now - lastNetTime) / 1000.0);
            sentRate = Math.max(0, (sent - lastSent) / 1024.0 / sec);
            recvRate = Math.max(0, (recv - lastRecv) / 1024.0 / sec);
        }
        lastSent = sent;
        lastRecv = recv;
        lastNetTime = now;
        return Map.of("sent", round1(sentRate), "recv", round1(recvRate));
    }

    /** AI 진단 정확도를 높이기 위한 세부 지표. 수집 못 한 항목은 빼고 보낸다. */
    public Map<String, Object> detailMetrics() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("cpu_cores", cpu.getLogicalProcessorCount());
        double[] load = cpu.getSystemLoadAverage(3); // 1/5/15분 평균 부하 (미지원 OS는 음수)
        if (load[0] >= 0) {
            d.put("load_avg", List.of(round2(load[0]), round2(load[1]), round2(load[2])));
        }
        d.put("context_switches", cpu.getContextSwitches());

        // Cached/Buffers는 리눅스에서만 제공되는 개념이라 /proc/meminfo가 있을 때만 수집
        Map<String, Long> meminfo = readLinuxMeminfo();
        if (meminfo.containsKey("Cached")) {
            d.put("mem_cached_mb", round1(meminfo.get("Cached") / 1024.0));
        }
        if (meminfo.containsKey("Buffers")) {
            d.put("mem_buffers_mb", round1(meminfo.get("Buffers") / 1024.0));
        }
        d.put("mem_available_mb", round1(memory.getAvailable() / MB));

        VirtualMemory vm = memory.getVirtualMemory();
        long swapTotal = vm.getSwapTotal();
        d.put("swap_used_percent", swapTotal > 0 ? round1(vm.getSwapUsed() * 100.0 / swapTotal) : 0.0);
        d.put("swap_used_mb", round1(vm.getSwapUsed() / MB));

        long read = 0, write = 0;
        for (HWDiskStore disk : hal.getDiskStores()) {
            read += disk.getReadBytes();
            write += disk.getWriteBytes();
        }
        d.put("disk_read_mb", round1(read / MB));
        d.put("disk_write_mb", round1(write / MB));
        return d;
    }

    private static Map<String, Long> readLinuxMeminfo() {
        Map<String, Long> m = new HashMap<>();
        Path path = Path.of("/proc/meminfo");
        if (!Files.exists(path)) {
            return m;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                String[] parts = line.split("\\s+"); // 예: "Cached:  123456 kB"
                if (parts.length >= 2) {
                    m.put(parts[0].replace(":", ""), Long.parseLong(parts[1]));
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // 세부 지표는 보조 정보이므로 실패해도 진단은 계속 진행
        }
        return m;
    }

    public static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
