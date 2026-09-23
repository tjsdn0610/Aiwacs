package com.sysone.aiwacs.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * 이 서버의 지표를 수집한다 (OSHI).
 * CPU·트래픽·디스크 I/O·페이지폴트처럼 "변화량"이 필요한 값은
 * 직전 수집 이후 기간 동안의 초당 값으로 계산한다. 그래서 첫 호출은 기준점만 잡고 null을 돌려준다.
 */
public class Collector {

    private static final double MB = 1024.0 * 1024.0;

    private final HardwareAbstractionLayer hal;
    private final OperatingSystem os;
    private final CentralProcessor cpu;
    private final GlobalMemory memory;
    private final List<NetworkIF> netIfs;
    private final List<HWDiskStore> disks;

    // 직전 수집 값 (변화량 계산용)
    private long prevTime;
    private long[] prevCpuTicks;
    private Map<Integer, OSProcess> prevProcs = new HashMap<>();
    private long prevSent, prevRecv, prevPageIn, prevPageOut;
    private long[] prevRead, prevWrite, prevBusy;

    public Collector() {
        SystemInfo si = new SystemInfo();
        this.hal = si.getHardware();
        this.os = si.getOperatingSystem();
        this.cpu = hal.getProcessor();
        this.memory = hal.getMemory();
        this.netIfs = hal.getNetworkIFs();
        this.disks = hal.getDiskStores();
    }

    public String hostname() {
        return os.getNetworkParams().getHostName();
    }

    public String osName() {
        return os.getFamily() + " " + os.getVersionInfo().getVersion();
    }

    /** 지표 한 묶음 수집. 첫 호출은 기준점만 저장하고 null. */
    public Map<String, Object> collect() {
        long now = System.currentTimeMillis();
        boolean first = prevTime == 0;
        double sec = first ? 1 : Math.max(0.001, (now - prevTime) / 1000.0);
        long elapsedMs = Math.max(1, now - prevTime);

        // ===== CPU =====
        double cpuPercent = first ? 0 : cpu.getSystemCpuLoadBetweenTicks(prevCpuTicks) * 100;
        prevCpuTicks = cpu.getSystemCpuLoadTicks();

        // ===== 프로세스 (CPU/메모리 + I/O·페이지폴트 변화량) =====
        long totalMem = memory.getTotal();
        List<Map<String, Object>> procs = new ArrayList<>();
        List<Map<String, Object>> ioProcs = new ArrayList<>();
        long majorSum = 0, minorSum = 0;
        Map<Integer, OSProcess> snapshot = new HashMap<>();
        for (OSProcess p : os.getProcesses()) {
            snapshot.put(p.getProcessID(), p);
            OSProcess before = prevProcs.get(p.getProcessID());
            String name = p.getName() == null || p.getName().isBlank() ? "unknown" : p.getName();
            if (name.length() > 20) {
                name = name.substring(0, 20);
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", name);
            pm.put("cpu", round1(p.getProcessCpuLoadBetweenTicks(before) * 100));
            pm.put("mem", round1(p.getResidentMemory() * 100.0 / totalMem));
            procs.add(pm);

            if (before != null) {
                long major = Math.max(0, p.getMajorFaults() - before.getMajorFaults());
                long minor = Math.max(0, p.getMinorFaults() - before.getMinorFaults());
                long io = Math.max(0, (p.getBytesRead() + p.getBytesWritten())
                        - (before.getBytesRead() + before.getBytesWritten()));
                majorSum += major;
                minorSum += minor;
                if (io > 0 || major > 0) {
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("name", name);
                    im.put("io_kb_s", round1(io / 1024.0 / sec));
                    im.put("major_faults_s", round1(major / sec));
                    ioProcs.add(im);
                }
            }
        }
        prevProcs = snapshot;

        // ===== 네트워크 =====
        long sent = 0, recv = 0;
        for (NetworkIF nif : netIfs) {
            nif.updateAttributes();
            sent += nif.getBytesSent();
            recv += nif.getBytesRecv();
        }
        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("sent", first ? 0.0 : round1(Math.max(0, sent - prevSent) / 1024.0 / sec));
        traffic.put("recv", first ? 0.0 : round1(Math.max(0, recv - prevRecv) / 1024.0 / sec));
        prevSent = sent;
        prevRecv = recv;

        // ===== 디스크 I/O =====
        int n = disks.size();
        long[] read = new long[n], write = new long[n], busy = new long[n];
        long readDelta = 0, writeDelta = 0, maxQueue = 0;
        double maxBusy = 0;
        for (int i = 0; i < n; i++) {
            HWDiskStore d = disks.get(i);
            d.updateAttributes();
            read[i] = d.getReadBytes();
            write[i] = d.getWriteBytes();
            busy[i] = d.getTransferTime();
            maxQueue = Math.max(maxQueue, d.getCurrentQueueLength());
            if (!first) {
                readDelta += Math.max(0, read[i] - prevRead[i]);
                writeDelta += Math.max(0, write[i] - prevWrite[i]);
                maxBusy = Math.max(maxBusy, (busy[i] - prevBusy[i]) * 100.0 / elapsedMs);
            }
        }
        prevRead = read;
        prevWrite = write;
        prevBusy = busy;

        // ===== 스왑 페이지 =====
        VirtualMemory vm = memory.getVirtualMemory();
        long pageIn = vm.getSwapPagesIn();
        long pageOut = vm.getSwapPagesOut();
        long pageInDelta = Math.max(0, pageIn - prevPageIn);
        long pageOutDelta = Math.max(0, pageOut - prevPageOut);
        prevPageIn = pageIn;
        prevPageOut = pageOut;

        prevTime = now;
        if (first) {
            return null;
        }

        // ===== 결과 조립 =====
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("cpu", round1(cpuPercent));
        core.put("memory", round1((totalMem - memory.getAvailable()) * 100.0 / totalMem));
        core.put("disk", round1(rootDiskPercent()));

        Map<String, Object> io = new LinkedHashMap<>();
        io.put("disk_read_mb_s", round2(readDelta / MB / sec));
        io.put("disk_write_mb_s", round2(writeDelta / MB / sec));
        io.put("disk_busy_percent", round1(Math.min(100, maxBusy)));
        io.put("disk_queue_length", maxQueue);
        io.put("swap_page_in_s", round1(pageInDelta / sec));
        io.put("swap_page_out_s", round1(pageOutDelta / sec));
        io.put("major_faults_s", round1(majorSum / sec));
        io.put("minor_faults_s", round1(minorSum / sec));

        ioProcs.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("io_kb_s")).reversed());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("core", core);
        result.put("procs", topProcs(procs));
        result.put("partitions", partitions());
        result.put("traffic", traffic);
        result.put("detail", detailMetrics());
        result.put("io", io);
        result.put("topIo", ioProcs.stream().limit(5).toList());
        return result;
    }

    /** 전송량을 줄이기 위해 CPU 상위 15개 + 메모리 상위 10개만 보낸다 */
    private static List<Map<String, Object>> topProcs(List<Map<String, Object>> all) {
        List<Map<String, Object>> result = new ArrayList<>(all.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("cpu")).reversed())
                .limit(15).toList());
        all.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("mem")).reversed())
                .limit(10)
                .filter(m -> !result.contains(m))
                .forEach(result::add);
        return result;
    }

    private double rootDiskPercent() {
        return os.getFileSystem().getFileStores(true).stream()
                .filter(fs -> "/".equals(fs.getMount()))
                .findFirst()
                .map(Collector::usagePercent)
                .orElse(0.0);
    }

    /** 사용률 = used / (used + 사용자가 쓸 수 있는 여유공간) — df 명령과 같은 계산 */
    private static double usagePercent(OSFileStore fs) {
        long used = fs.getTotalSpace() - fs.getFreeSpace();
        long denom = used + fs.getUsableSpace();
        return denom > 0 ? used * 100.0 / denom : 0.0;
    }

    /** 파티션별 디스크 사용률 (사용률 높은 순 5개) */
    private List<Map<String, Object>> partitions() {
        return os.getFileSystem().getFileStores(true).stream()
                .filter(fs -> fs.getTotalSpace() > 0)
                .sorted(Comparator.comparingDouble(Collector::usagePercent).reversed())
                .limit(5)
                .map(fs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", fs.getMount());
                    m.put("percent", round1(usagePercent(fs)));
                    return m;
                })
                .toList();
    }

    /** AI 진단용 세부 지표. 수집 못 한 항목은 뺀다. */
    private Map<String, Object> detailMetrics() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("cpu_cores", cpu.getLogicalProcessorCount());
        double[] load = cpu.getSystemLoadAverage(3); // 1/5/15분 평균 부하 (미지원 OS는 음수)
        if (load[0] >= 0) {
            d.put("load_avg", List.of(round2(load[0]), round2(load[1]), round2(load[2])));
        }
        d.put("context_switches", cpu.getContextSwitches());

        // Cached/Buffers는 리눅스 전용 개념이라 /proc/meminfo가 있을 때만 수집
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
            // 세부 지표는 보조 정보이므로 실패해도 계속 진행
        }
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
