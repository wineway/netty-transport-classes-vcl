/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.lang.management.ManagementFactory;
import java.nio.file.*;

record ProcessMetrics(long cpuNanos, long allocatedBytes, long gcCount, long gcMillis) {
    static ProcessMetrics current() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long count = 0, millis = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, gc.getCollectionCount()); millis += Math.max(0, gc.getCollectionTime());
        }
        return new ProcessMetrics(os.getProcessCpuTime(), threads.getTotalThreadAllocatedBytes(), count, millis);
    }
    ProcessMetrics minus(ProcessMetrics before) {
        return new ProcessMetrics(cpuNanos - before.cpuNanos, allocatedBytes - before.allocatedBytes,
                gcCount - before.gcCount, gcMillis - before.gcMillis);
    }
    static long vppCpuNanos() throws Exception {
        long ticks = 0;
        for (String pid : System.getProperty("vcl.bench.vppPids", "").split(",")) {
            if (pid.isBlank()) continue;
            String stat = Files.readString(Path.of("/proc", pid, "stat"));
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            ticks += Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        }
        return ticks * 1_000_000_000L / Long.getLong("vcl.bench.clockTicks", 100);
    }
}
