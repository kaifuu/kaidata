package com.pharma.service.access.develop;

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
 * 离线任务周期调度：ONLINE 且配 cron(秒) 的任务按间隔触发 DevOfflineExecutor.run。
 * 同 ProfileScheduler / DevWorkflowExecutor 模式（非真 cron）；进程重启后需重新上线触发。
 */
@Component
public class DevOfflineScheduler {

    @Autowired private DevOfflineExecutor executor;

    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "dev-offline-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public void start(long taskId, String cronSec) {
        stop(taskId);
        int sec = Math.max(parseSec(cronSec), 30);
        tasks.put(taskId, pool.scheduleAtFixedRate(() -> safeRun(taskId), sec, sec, TimeUnit.SECONDS));
    }

    public void stop(long taskId) {
        ScheduledFuture<?> f = tasks.remove(taskId);
        if (f != null) f.cancel(false);
    }

    private void safeRun(long taskId) {
        try { executor.run(taskId); } catch (Exception e) {
            System.err.println("[DevOffline] task " + taskId + " 周期失败: " + e.getMessage());
        }
    }

    private static int parseSec(String s) {
        if (s == null || s.isBlank()) return 300;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 300; }
    }

    @PreDestroy
    public void shutdown() { pool.shutdownNow(); }
}
