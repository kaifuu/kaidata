package com.pharma.service.controller;

import com.pharma.service.access.service.DataServiceExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 公开数据服务端点：/open/{code} —— AuthFilter 只拦 /api/**，故 /open/** 免鉴权（对外发布给第三方）。
 * 仅 status=PUBLISHED 的服务可被调用；调用记日志（caller=anonymous）。
 */
@RestController
@RequestMapping("/open")
@CrossOrigin(origins = "*")
public class OpenServiceController {

    @Autowired private DataServiceExecutor executor;

    @GetMapping("/{code}")
    public Map<String, Object> invoke(@PathVariable String code, @RequestParam Map<String, String> params, HttpServletRequest req) {
        return executor.invoke(code, params, "anonymous", req.getRemoteAddr());
    }
}
