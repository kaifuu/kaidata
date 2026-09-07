package com.pharma.service.access.layer;

import com.pharma.service.controller.DataWhController;
import com.pharma.service.security.AlertService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数仓规划定时任务：每日容量快照（画像趋势数据源）+ 每日命名巡检（违规落库并告警）。
 * 启动时补记当日快照（当日未记才写，幂等）；巡检发现新违规 → sec_alert_event 告警事件。
 */
@Component
public class WhScheduler {

    @Autowired private DataWhController wh;
    @Autowired private AlertService alertService;

    /** 启动补记当日容量快照（StarRocks 未就绪等异常吞掉，等每日定时再补）。 */
    @PostConstruct
    public void onStartup() {
        try { wh.snapshotLayerStats(); } catch (Exception ignored) {}
    }

    /** 每日 01:37 记分层容量快照。 */
    @Scheduled(cron = "0 37 1 * * ?")
    public void dailySnapshot() {
        try { wh.snapshotLayerStats(); } catch (Exception ignored) {}
    }

    /** 每日 02:43 命名巡检（落库+自动 RESOLVED），有未结违规即告警。 */
    @Scheduled(cron = "0 43 2 * * ?")
    public void dailyNamingCheck() {
        try {
            Map<String, Object> r = wh.runNamingCheckCore();
            int violate = ((Number) r.getOrDefault("violate", 0)).intValue();
            int checked = ((Number) r.getOrDefault("checked", 0)).intValue();
            if (violate > 0) {
                alertService.raise("MAJOR", "命名巡检：发现 " + violate + "/" + checked + " 张表不符合分层命名规范，请到 数据仓库→命名巡检 处理");
            }
        } catch (Exception ignored) {}
    }
}
