package com.pharma.service.access.meta;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 元数据采集周期调度：每个 ONLINE 且配了 cron(秒) 的采集任务按间隔周期触发 MetaCollectExecutor.run。
 * 与 ProfileScheduler / DevWorkflowExecutor 同模式（非真 cron）；进程重启后需重新上线触发。
 */
@Component
public class MetaCollectScheduler {

    @Autowired private MetaCollectExecutor executor;

    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "meta-collect-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public void start(long jobId, String cronSec) {
        stop(jobId);
        int sec = Math.max(parseSec(cronSec), 30);
        ScheduledFuture<?> f = pool.scheduleAtFixedRate(() -> safeRun(jobId), sec, sec, TimeUnit.SECONDS);
        tasks.put(jobId, f);
    }

    public void stop(long jobId) {
        ScheduledFuture<?> f = tasks.remove(jobId);
        if (f != null) f.cancel(false);
    }

    public boolean isRunning(long jobId) { return tasks.containsKey(jobId); }

    private void safeRun(long jobId) {
        try { executor.run(jobId); } catch (Exception e) {
            System.err.println("[MetaCollectScheduler] job " + jobId + " 周期执行失败: " + e.getMessage());
        }
    }

    private static int parseSec(String s) {
        if (s == null || s.isBlank()) return 60;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 60; }
    }

    @PreDestroy
    public void shutdown() { pool.shutdownNow(); }
}
