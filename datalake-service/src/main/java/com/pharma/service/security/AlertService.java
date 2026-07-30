package com.pharma.service.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * 告警事件服务：供数据流各环节（接入失败/质量异常/服务调用异常）统一落 sec_alert_event，
 * 实现「数据流 FAIL → 告警」闭环。容错，异常绝不阻断主流程。
 */
@Component
public class AlertService {

    @Autowired private JdbcTemplate jdbc;

    /** 触发一条告警事件。level: CRITICAL / MAJOR / MINOR。 */
    public void raise(String level, String message) {
        try {
            jdbc.update("INSERT INTO meta.sec_alert_event(id, def_id, level, message, status, created_time) VALUES (?,?,?,?,?,?)",
                    System.currentTimeMillis(), 0L, level, message == null ? "" : message, "未处理", new Timestamp(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }
}
