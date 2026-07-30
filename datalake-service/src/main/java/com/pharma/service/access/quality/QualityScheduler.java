package com.pharma.service.access.quality;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 质量检测周期调度（仿 DevOfflineScheduler）：把质量检测从「仅手动按钮」串入流水线。
 * cron 字段为秒间隔（最小 30s）。启动时恢复 status='ENABLED' 的任务。
 */
@Component
public class QualityScheduler {

    @Autowired private QualityExecutor executor;
    @Autowired private JdbcTemplate jdbc;

    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "quality-scheduler"); t.setDaemon(true); return t;
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
            System.err.println("[Quality] task " + taskId + " 周期失败: " + e.getMessage());
        }
    }

    /** 启动恢复 status='ENABLED' 的质量任务。 */
    @PostConstruct
    public void restore() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, cron FROM meta.gov_quality_task WHERE status='ENABLED'");
            for (Map<String, Object> r : rows) {
                try { start(((Number) r.get("id")).longValue(), String.valueOf(r.get("cron"))); } catch (Exception ignored) {}
            }
            if (!rows.isEmpty()) System.out.println("[QualityScheduler] 恢复 " + rows.size() + " 个质量任务");
        } catch (Exception ignored) {}
    }

    @PreDestroy
    public void shutdown() { pool.shutdownNow(); }

    private static int parseSec(String s) { try { return Math.max(Integer.parseInt(s.trim()), 1); } catch (Exception e) { return 300; } }
}
