package com.pharma.service.access.profile;

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
 * 探查任务周期调度：每个 ONLINE 且配了 cron 的任务按秒间隔周期性触发 ProfileExecutor.run。
 * <p>cron 简化为秒数（同 JdbcToKafkaRunner 模式）；非真 cron 表达式。任务进程重启后需重新上线触发。
 */
@Component
public class ProfileScheduler {

    @Autowired private ProfileExecutor executor;

    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "profile-scheduler"); t.setDaemon(true); return t;
    });
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public void start(long jobId, String cronSec) {
        stop(jobId);
        int sec = Math.max(parseSec(cronSec), 10);
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
            System.err.println("[ProfileScheduler] job " + jobId + " 周期执行失败: " + e.getMessage());
        }
    }

    private static int parseSec(String s) {
        if (s == null || s.isBlank()) return 60;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 60; }
    }

    @PreDestroy
    public void shutdown() { pool.shutdownNow(); }
}
