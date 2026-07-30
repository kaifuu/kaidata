package com.pharma.service.controller;

import com.pharma.service.access.service.DataServiceExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开数据服务端点：/open/{code} —— AuthFilter 只拦 /api/**，故 /open/** 免鉴权。
 * <p>仅 auth=false（显式公开）且 PUBLISHED 的服务可经此端点调用；订阅派生的服务 auth=true，
 * 必须经 /openapi/{appKey} 走 appKey+secret+限流鉴权，避免被枚举 open_{assetId}_* 绕过。
 */
@RestController
@RequestMapping("/open")
@CrossOrigin(origins = "*")
public class OpenServiceController {

    @Autowired private DataServiceExecutor executor;
    @Autowired private JdbcTemplate jdbc;

    @GetMapping("/{code}")
    public Map<String, Object> invoke(@PathVariable String code, @RequestParam Map<String, String> params, HttpServletRequest req) {
        Boolean auth;
        try {
            auth = jdbc.queryForObject("SELECT auth FROM meta.data_service WHERE code=? AND status='PUBLISHED'", Boolean.class, code);
        } catch (Exception e) {
            return err("服务不存在或未发布: " + code);
        }
        // 订阅派生服务 auth=true → 必须经 /openapi 鉴权调用，/open 拒绝（防免鉴权绕过）
        if (Boolean.TRUE.equals(auth)) return err("该服务需经 /openapi/{appKey} 鉴权调用");
        return executor.invoke(code, params, "anonymous", req.getRemoteAddr());
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "FAIL");
        m.put("msg", msg);
        return m;
    }
}
