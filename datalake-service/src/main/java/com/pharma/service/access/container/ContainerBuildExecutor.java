package com.pharma.service.access.container;

import com.pharma.service.access.util.CryptoUtil;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 容器镜像构建/部署异步执行器。仿 DevOfflineScheduler：固定线程池 + 内存 live 态供前端轮询，
 * 完成时一次性写追加型历史表（ct_image_build_run / ct_deploy_record，DUPLICATE KEY）。
 * <p>
 * 进程内幂等：同一 versionId/deployId 重复提交覆盖其 live 态。
 */
@Component
public class ContainerBuildExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DockerCli docker;
    @Autowired private CryptoUtil crypto;

    @Value("${pharma.image.dir:data/images}") private String imageDir;
    @Value("${pharma.image.context-dir:.}") private String contextDir;
    @Value("${pharma.image.dockerfile:docker/app/Dockerfile}") private String dockerfilePath;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "container-build");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, LiveState> live = new ConcurrentHashMap<>();

    // ============ 构建 ============
    public long submitBuild(long versionId, String user) {
        long runId = System.currentTimeMillis();
        live.put(versionId, new LiveState("RUNNING", "提交构建任务...\n", runId));
        pool.submit(() -> doBuild(versionId, runId, user));
        return runId;
    }

    private void doBuild(long versionId, long runId, String user) {
        LiveState st = live.get(versionId);
        long start = System.currentTimeMillis();
        String err = "";
        boolean ok = false;
        try {
            Map<String, Object> v = jdbc.queryForMap(
                    "SELECT name, tag FROM meta.ct_image_version WHERE id=?", versionId);
            String name = str(v.get("name"));
            String tag = str(v.get("tag"));
            String fullTag = (tag.isEmpty() ? name : name + ":" + tag);

            append(st, "$ docker build -t " + fullTag + " -f " + dockerfilePath + " " + contextDir + "\n");
            DockerCli.ExecResult b = docker.build(fullTag, contextDir, dockerfilePath);
            append(st, b.log);
            if (!b.success) throw new RuntimeException("构建失败: " + b.error);
            append(st, "\n构建完成，读取镜像信息...\n");

            Map<String, Object> ins = docker.inspect(fullTag);
            String imageId = str(ins.get("imageId"));
            long sizeBytes = lng(ins.get("sizeBytes"));
            append(st, "镜像 " + imageId + " / " + sizeBytes + " bytes\n");

            // docker save 导出 tar
            Path dir = Paths.get(imageDir);
            Files.createDirectories(dir);
            String tarName = name.replace('/', '_') + (tag.isEmpty() ? "" : "_" + tag) + "_" + runId + ".tar";
            Path tar = dir.resolve(tarName);
            append(st, "$ docker save -o " + tarName + " " + fullTag + "\n");
            DockerCli.ExecResult sv = docker.save(fullTag, tar);
            append(st, sv.log);
            if (!sv.success) throw new RuntimeException("保存 tar 失败: " + sv.error);
            long fileSize = Files.size(tar);

            jdbc.update("UPDATE meta.ct_image_version SET status='SAVED', image_id=?, size_bytes=?, " +
                            "file_path=?, file_size=?, update_time=? WHERE id=?",
                    imageId, sizeBytes, tarName, fileSize, new Timestamp(System.currentTimeMillis()), versionId);
            ok = true;
            append(st, "\n✓ 构建并保存成功: " + tarName + " (" + fileSize + " bytes)\n");
        } catch (Exception e) {
            err = rootMsg(e);
            append(st, "\n✗ " + err + "\n");
            try {
                jdbc.update("UPDATE meta.ct_image_version SET status='FAIL', update_time=? WHERE id=?",
                        new Timestamp(System.currentTimeMillis()), versionId);
            } catch (Exception ignored) {}
        } finally {
            try {
                jdbc.update("INSERT INTO meta.ct_image_build_run(id, version_id, action, status, log_text, start_time, end_time, error_msg, triggered_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)", runId, versionId, "BUILD", ok ? "SUCCESS" : "FAIL", st.log,
                        new Timestamp(start), new Timestamp(System.currentTimeMillis()), err, user);
            } catch (Exception ignored) {}
            st.status = ok ? "SUCCESS" : "FAIL";
            st.finishAt = System.currentTimeMillis();
        }
    }

    // ============ 部署 ============
    public long submitDeploy(long versionId, long serverId, String user) {
        long deployId = System.currentTimeMillis();
        live.put(deployId, new LiveState("RUNNING", "开始部署...\n", deployId));
        pool.submit(() -> doDeploy(versionId, serverId, deployId, user));
        return deployId;
    }

    private void doDeploy(long versionId, long serverId, long deployId, String user) {
        LiveState st = live.get(deployId);
        long start = System.currentTimeMillis();
        String err = "";
        boolean ok = false;
        try {
            Map<String, Object> v = jdbc.queryForMap(
                    "SELECT name, tag, file_path FROM meta.ct_image_version WHERE id=?", versionId);
            Map<String, Object> s = jdbc.queryForMap(
                    "SELECT host, ssh_port, username, password, deploy_path, docker_bin FROM meta.ct_server WHERE id=?", serverId);
            String name = str(v.get("name"));
            String tag = str(v.get("tag"));
            String tarName = str(v.get("file_path"));
            String fullTag = (tag.isEmpty() ? name : name + ":" + tag);
            String host = str(s.get("host"));
            String srvUser = str(s.get("username"));
            int port = (int) lng(s.get("ssh_port"));
            String pwd = crypto.decrypt(str(s.get("password")));
            String deployPath = str(s.get("deploy_path"));
            if (deployPath.isEmpty()) deployPath = "/opt/images";
            String dockerBin = str(s.get("docker_bin"));
            if (dockerBin.isEmpty()) dockerBin = "docker";

            RemoteCmdExec.Conn c = new RemoteCmdExec.Conn(host, port, srvUser, pwd);
            String remote = deployPath + "/" + tarName;

            // 1. 探测远端 docker + 建目录
            append(st, "探测远端 docker 并创建目录 " + deployPath + " ...\n");
            RemoteCmdExec.ExecResult mk = RemoteCmdExec.runCmd(c,
                    dockerBin + " version --format '{{.Server.Version}}' && mkdir -p " + deployPath, 30);
            append(st, mk.log);
            if (!mk.ok) throw new RuntimeException("远端不可用或目录创建失败: " + mk.err);

            // 2. 流式 SFTP 上传 tar
            append(st, "SFTP 上传 " + tarName + " -> " + host + ":" + remote + " ...\n");
            Path local = Paths.get(imageDir).resolve(tarName);
            try (InputStream in = Files.newInputStream(local)) {
                RemoteCmdExec.uploadStream(c, remote, in);
            }
            append(st, "上传完成\n");

            // 3. docker load
            append(st, "$ " + dockerBin + " load -i " + remote + "\n");
            RemoteCmdExec.ExecResult ld = RemoteCmdExec.runCmd(c, dockerBin + " load -i " + remote, 1800);
            append(st, ld.log);
            if (!ld.ok) throw new RuntimeException("docker load 失败: " + ld.err);
            append(st, "\n✓ 部署成功：镜像 " + fullTag + " 已载入 " + host + "\n");
            ok = true;
        } catch (Exception e) {
            err = rootMsg(e);
            append(st, "\n✗ " + err + "\n");
        } finally {
            try {
                jdbc.update("INSERT INTO meta.ct_deploy_record(id, version_id, server_id, status, log_text, start_time, end_time, error_msg, triggered_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)", deployId, versionId, serverId, ok ? "SUCCESS" : "FAIL", st.log,
                        new Timestamp(start), new Timestamp(System.currentTimeMillis()), err, user);
            } catch (Exception ignored) {}
            st.status = ok ? "SUCCESS" : "FAIL";
            st.finishAt = System.currentTimeMillis();
        }
    }

    public LiveState liveStatus(long id) { return live.get(id); }

    /** 清理 10 分钟前已完成的 live 态，避免内存泄漏。 */
    public void sweep() {
        long now = System.currentTimeMillis();
        live.values().removeIf(st -> st.finishAt > 0 && now - st.finishAt > 600000);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
        try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    // ---- 助手 ----
    private void append(LiveState st, String s) { if (st != null) st.log += s; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
    private static String rootMsg(Throwable e) {
        Throwable c = e;
        for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    public static class LiveState {
        public String status;
        public String log;
        public final long runId;
        public long finishAt;
        public LiveState(String status, String log, long runId) { this.status = status; this.log = log; this.runId = runId; }
    }
}
