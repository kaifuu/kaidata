package com.pharma.service.access.container;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本机 docker CLI 封装（ProcessBuilder）。仿 DevScriptExecutor.runProcess：
 * redirectErrorStream + 超时 + 输出捕获。容器管理模块用它做 build / save / inspect。
 * <p>
 * 不引入 docker-java SDK：依赖本机已安装 docker（Windows 为 Docker Desktop）。
 */
@Component
public class DockerCli {

    @Value("${pharma.docker.bin:docker}") private String bin;
    @Value("${pharma.docker.build-timeout-sec:1800}") private long timeout;

    /** docker 是否可用（构建前探测）。 */
    public boolean available() {
        return runTimed("version", "--format", "{{.Server.Version}}").success;
    }

    /** docker 版本信息（前端展示 + 排查）。 */
    public String info() {
        ExecResult r = runTimed("version", "--format", "Server {{.Server.Version}} / Client {{.Client.Version}}");
        return r.success ? r.log : ("docker 不可用: " + r.error);
    }

    /** docker build -t tag -f dockerfile contextDir。 */
    public ExecResult build(String tag, String contextDir, String dockerfile) {
        return runTimed("build", "-t", tag, "-f", dockerfile, contextDir);
    }

    /** docker save -o tarPath tag（写文件，不走 stdout，无内存压力）。 */
    public ExecResult save(String tag, Path tarPath) {
        return runTimed("save", "-o", tarPath.toString(), tag);
    }

    /** docker image inspect --format 取 Id 与 Size（用 Go template，避免手解 JSON）。 */
    public Map<String, Object> inspect(String tag) {
        Map<String, Object> out = new LinkedHashMap<>();
        ExecResult r = runTimed("image", "inspect", "--format", "{{.Id}}|{{.Size}}", tag);
        out.put("ok", r.success);
        if (r.success) {
            String[] parts = r.log.trim().split("\\|");
            if (parts.length > 0) out.put("imageId", parts[0].trim());
            if (parts.length > 1) {
                try { out.put("sizeBytes", Long.parseLong(parts[1].trim())); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    // ---- 私有 ----
    private ExecResult runTimed(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        Collections.addAll(cmd, args);
        ExecResult r = new ExecResult();
        StringBuilder out = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null && out.length() < 200000) out.append(line).append('\n');
            }
            boolean done = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                r.error = "命令超时（" + timeout + "s）";
            } else if (p.exitValue() != 0) {
                r.error = "退出码 " + p.exitValue();
            }
            r.success = done && p.exitValue() == 0;
        } catch (Exception e) {
            r.success = false;
            r.error = rootMsg(e);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
        r.log = out.toString();
        return r;
    }

    private static String rootMsg(Throwable e) {
        Throwable c = e;
        for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    public static class ExecResult {
        public boolean success;
        public String log = "";
        public String error = "";
    }
}
