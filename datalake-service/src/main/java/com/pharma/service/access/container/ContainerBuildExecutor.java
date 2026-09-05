package com.pharma.service.access.container;

import com.pharma.service.access.util.CryptoUtil;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** 随部署附带大数据栈时上传的编排资产（相对仓库 docker/ 目录；缺失跳过并记日志）。 */
    private static final String[] STACK_FILES = {
            "docker-compose.yml", "hop-jdbc/mysql-connector-j-8.3.0.jar",
            "iceberg-rest-aws/Dockerfile",       // 湖 REST Catalog 自建镜像（compose build 用，远端拉 jar 自行构建）
            "init/doris-ddl.sql", "init/kafka-topics.sh", "init/minio-init.sh", "init/iceberg-catalog.sql"};
    /** meta 逻辑导出排除的运行历史表（远端保留自己的构建/部署/审计记录，导出保持"基础数据"体积）。 */
    private static final List<String> DUMP_EXCLUDE = List.of("sys_audit_log", "ct_image_build_run", "ct_deploy_record");

    // ============ 构建 ============
    public long submitBuild(long versionId, String user) {
        sweep();   // 顺手清理过期 live 态（原先无人调用，10 分钟兜底清理成死代码）
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
                    "SELECT name, tag, file_path, expose_port FROM meta.ct_image_version WHERE id=?", versionId);
            String oldTar = str(v.get("file_path"));   // 重复构建后旧 tar 成为孤儿，保存成功即清理
            String name = str(v.get("name"));
            String tag = str(v.get("tag"));
            String fullTag = (tag.isEmpty() ? name : name + ":" + tag);
            String appPort = portOf(v.get("expose_port"));   // 烧进镜像的 nginx 监听端口

            append(st, "$ docker build -t " + fullTag + " --build-arg APP_PORT=" + appPort + " -f " + dockerfilePath + " " + contextDir + "\n");
            // 流式回调：每行实时进 live 日志，前端轮询即滚屏
            DockerCli.ExecResult b = docker.build(fullTag, contextDir, dockerfilePath,
                    Map.of("APP_PORT", appPort), line -> append(st, line + "\n"));
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
            DockerCli.ExecResult sv = docker.save(fullTag, tar, line -> append(st, line + "\n"));
            if (!sv.success) throw new RuntimeException("保存 tar 失败: " + sv.error);
            long fileSize = Files.size(tar);

            jdbc.update("UPDATE meta.ct_image_version SET status='SAVED', image_id=?, size_bytes=?, " +
                            "file_path=?, file_size=?, update_time=? WHERE id=?",
                    imageId, sizeBytes, tarName, fileSize, new Timestamp(System.currentTimeMillis()), versionId);
            if (!oldTar.isEmpty() && !oldTar.equals(tarName)) {
                try { Files.deleteIfExists(Paths.get(imageDir).resolve(oldTar)); } catch (Exception ignored) {}
            }
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
    public long submitDeploy(long versionId, long serverId, String user, boolean withStack, boolean withData, String dumpFile) {
        sweep();
        long deployId = System.currentTimeMillis();
        live.put(deployId, new LiveState("RUNNING", "开始部署...\n", deployId));
        pool.submit(() -> doDeploy(versionId, serverId, deployId, user, withStack, withData, dumpFile));
        return deployId;
    }

    private void doDeploy(long versionId, long serverId, long deployId, String user,
                          boolean withStack, boolean withData, String dumpFile) {
        LiveState st = live.get(deployId);
        long start = System.currentTimeMillis();
        String err = "";
        boolean ok = false;
        try {
            Map<String, Object> v = jdbc.queryForMap(
                    "SELECT name, tag, file_path, expose_port FROM meta.ct_image_version WHERE id=?", versionId);
            Map<String, Object> s = jdbc.queryForMap(
                    "SELECT host, ssh_port, username, password, auth_type, private_key, key_passphrase, use_sudo, sudo_password, auto_start, run_port, container_name, run_env, deploy_path, docker_bin FROM meta.ct_server WHERE id=?", serverId);
            String name = str(v.get("name"));
            String tag = str(v.get("tag"));
            String tarName = str(v.get("file_path"));
            String fullTag = (tag.isEmpty() ? name : name + ":" + tag);
            String host = str(s.get("host"));
            String srvUser = str(s.get("username"));
            int port = (int) lng(s.get("ssh_port"));
            String authType = str(s.get("auth_type"));
            boolean useSudo = "ON".equals(str(s.get("use_sudo")));
            String sudoPwd = crypto.decrypt(str(s.get("sudo_password")));
            RemoteCmdExec.Conn c = new RemoteCmdExec.Conn(host, port, srvUser, crypto.decrypt(str(s.get("password"))),
                    authType, crypto.decrypt(str(s.get("private_key"))), crypto.decrypt(str(s.get("key_passphrase"))));
            append(st, "认证方式：" + ("KEY".equals(authType) ? "秘钥文件" : "密码")
                    + (useSudo ? " + sudo 提权" : "") + "\n");
            String deployPath = str(s.get("deploy_path"));
            if (deployPath.isEmpty()) deployPath = "/opt/images";
            String dockerBin = str(s.get("docker_bin"));
            if (dockerBin.isEmpty()) dockerBin = "docker";

            // 1. 探测远端环境：部署目录可写性 + docker 直连权限（一次往返，标记词防误匹配）
            append(st, "探测远端环境（目录权限 / docker 权限）...\n");
            RemoteCmdExec.ExecResult probe = RemoteCmdExec.runCmd(c,
                    "test -w " + deployPath + " && echo CT_DIR_WOK || echo CT_DIR_WNO; " +
                    dockerBin + " version --format '{{.Server.Version}}' >/dev/null 2>&1 && echo CT_DOK || echo CT_DNO", 30);
            String pl = probe.log == null ? "" : probe.log;
            boolean dirWritable = pl.contains("CT_DIR_WOK");
            boolean dockerDirect = pl.contains("CT_DOK");
            append(st, "  部署目录 " + deployPath + (dirWritable ? " 可写" : " 不可写")
                    + "；docker 直连" + (dockerDirect ? "可用" : "不可用") + "\n");

            // sudo 决策：显式开启，或 docker 无法直连且已配 sudo 密码
            boolean sudoLoad = useSudo || (!dockerDirect && !sudoPwd.isEmpty());
            if (sudoLoad && sudoPwd.isEmpty())
                throw new RuntimeException("需要 sudo 提权但未配置 sudo 密码（在发布目标里开启 sudo 并填写）");

            // 2. 上传目录：deploy_path 不可写时回退 /tmp（SFTP 无法提权）
            String uploadDir = dirWritable ? deployPath : "/tmp";
            if (!dirWritable) append(st, "  " + deployPath + " 不可写，上传回退 " + uploadDir + "\n");
            if (dirWritable) {
                RemoteCmdExec.ExecResult mk = RemoteCmdExec.runCmd(c, "mkdir -p " + deployPath, 30);
                if (!mk.ok) throw new RuntimeException("目录创建失败: " + mk.err);
            }

            // 3. sudo 预验证（docker version 走 sudo -S，密码经 stdin 注入，远端 ps/历史不可见）
            if (sudoLoad) {
                append(st, "$ sudo -S " + dockerBin + " version（验证 sudo 密码）\n");
                RemoteCmdExec.ExecResult sv = RemoteCmdExec.runCmd(c,
                        "sudo -S -p '' " + dockerBin + " version --format '{{.Server.Version}}'", 30, sudoPwd);
                append(st, sv.log);
                if (!sv.ok) throw new RuntimeException("sudo 验证失败（检查 sudo 密码/权限）: " + sv.err);
            }

            // 4. 流式 SFTP 上传 tar
            String remote = uploadDir + "/" + tarName;
            append(st, "SFTP 上传 " + tarName + " -> " + host + ":" + remote + " ...\n");
            Path local = Paths.get(imageDir).resolve(tarName);
            try (InputStream in = Files.newInputStream(local)) {
                RemoteCmdExec.uploadStream(c, remote, in);
            }
            append(st, "上传完成\n");

            // 5. docker load（按需 sudo，超时 30min；逐行流式回调，load 层数时前端也能滚屏）
            String loadCmd = sudoLoad ? "sudo -S -p '' " + dockerBin + " load -i " + remote
                                      : dockerBin + " load -i " + remote;
            append(st, "$ " + (sudoLoad ? "sudo " : "") + dockerBin + " load -i " + remote + "\n");
            RemoteCmdExec.ExecResult ld = RemoteCmdExec.runCmd(c, loadCmd, 1800, sudoLoad ? sudoPwd : null,
                    line -> append(st, line + "\n"));
            if (!ld.ok) throw new RuntimeException("docker load 失败: " + ld.err);

            // 6. 清理 /tmp 暂存（失败不阻塞）
            if (!dirWritable) {
                String rmCmd = sudoLoad ? "sudo -S -p '' rm -f " + remote : "rm -f " + remote;
                RemoteCmdExec.ExecResult rm = RemoteCmdExec.runCmd(c, rmCmd, 30, sudoLoad ? sudoPwd : null);
                append(st, rm.ok ? "已清理暂存 " + remote + "\n" : "暂存清理失败（不影响部署）: " + rm.err + "\n");
            }
            append(st, "\n✓ 镜像已载入 " + host + "\n");

            String sud = sudoLoad ? "sudo -S -p '' " : "";
            String pwd = sudoLoad ? sudoPwd : null;

            // 6.5 附带大数据栈（withStack）：上传编排资产 → 远端 compose up（自行拉镜像）→ 等 StarRocks → 初始化数仓分层库
            if (withStack) deployStack(c, sud, pwd, dockerBin, uploadDir, st);

            // 7. 自动启动（auto_start=ON）：替换旧容器 → docker run -d → 状态/HTTP 探活
            boolean autoStart = "ON".equals(str(s.get("auto_start")));
            if (autoStart) {
                String cname = str(s.get("container_name"));
                if (cname.isEmpty()) cname = name.replace('/', '_');
                int runPort = (int) lng(s.get("run_port"));
                if (runPort <= 0) runPort = 80;
                String cPort = portOf(v.get("expose_port"));   // 容器端口=镜像内 nginx 监听端口（构建时烧入）
                append(st, "\n自动启动容器 " + cname + "（宿主端口 " + runPort + " → 容器 " + cPort + "）...\n");
                // 7.1 替换同名旧容器（首次部署不存在则忽略）
                RemoteCmdExec.ExecResult rm0 = RemoteCmdExec.runCmd(c, sud + dockerBin + " rm -f " + cname, 60, pwd);
                if (!rm0.ok) append(st, "  无旧容器\n");
                // 7.2 启动：unless-stopped 掉线自拉起；host-gateway 便于容器内经 host.docker.internal 访问宿主服务
                StringBuilder run = new StringBuilder(dockerBin + " run -d --name " + cname
                        + " --restart unless-stopped --add-host host.docker.internal:host-gateway"
                        + " -p " + runPort + ":" + cPort);
                // withStack 时兜底注入依赖地址（KEY=VALUE 合并，用户 run_env 显式配置的同名 KEY 优先）
                LinkedHashMap<String, String> env = new LinkedHashMap<>();
                List<String> injected = new ArrayList<>();   // 最终生效的注入 KEY 名（仅记名不记值）
                if (withStack) {
                    env.put("STARROCKS_HOST", "host.docker.internal");
                    env.put("STARROCKS_PORT", "9030");
                    env.put("KAFKA_BOOTSTRAP", "host.docker.internal:9094");
                    env.put("FLINK_REST", "http://host.docker.internal:8081");
                    env.put("FLINK_SQL_GATEWAY", "http://host.docker.internal:8083");
                    env.put("HOP_SERVER", "http://host.docker.internal:8082");
                    injected.addAll(env.keySet());
                }
                int rawCnt = 0;
                for (String line : str(s.get("run_env")).split("\n")) {
                    String l = line.trim();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    int eq = l.indexOf('=');
                    if (eq > 0) {
                        env.put(l.substring(0, eq).trim(), l.substring(eq + 1));
                        injected.remove(l.substring(0, eq).trim());
                    } else {
                        run.append(" -e ").append(shq(l));   // 无 '=' 的行原样透传（旧行为）
                        rawCnt++;
                    }
                }
                for (Map.Entry<String, String> e : env.entrySet())
                    run.append(" -e ").append(shq(e.getKey() + "=" + e.getValue()));   // 值整体单引号包裹，支持含 = & 等字符
                if (withStack) append(st, "注入默认环境变量(用户未覆盖): " + String.join(", ", injected) + "\n");
                run.append(" ").append(shq(fullTag));
                // 日志不回显 -e 值（可能含密码），只报数量
                append(st, "$ " + (sudoLoad ? "sudo " : "") + dockerBin + " run -d --name " + cname
                        + " --restart unless-stopped -p " + runPort + ":" + cPort + " -e ×" + (env.size() + rawCnt) + " " + fullTag + "\n");
                RemoteCmdExec.ExecResult rn = RemoteCmdExec.runCmd(c, sud + run, 120, pwd);
                append(st, rn.log);
                if (!rn.ok) throw new RuntimeException("容器启动失败: " + rn.err);
                // 7.3 容器状态 + 宿主端口 HTTP 探活（最多 20s 重试）
                RemoteCmdExec.ExecResult ps = RemoteCmdExec.runCmd(c,
                        sud + dockerBin + " ps --filter name=^/" + cname + "$ --format '{{.Status}}'", 30, pwd);
                append(st, "容器状态: " + (ps.log == null ? "" : ps.log.trim()) + "\n");
                RemoteCmdExec.ExecResult http = RemoteCmdExec.runCmd(c,
                        "for i in 1 2 3 4 5 6 7 8 9 10; do code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:" + runPort + "/ 2>/dev/null || echo ERR); [ \"$code\" = \"200\" ] && break; sleep 2; done; echo CT_HTTP_$code",
                        90, null);
                String hl = http.log == null ? "" : http.log.trim();
                if (hl.endsWith("200")) append(st, "✓ 服务已就绪：http://" + host + ":" + runPort + "/\n");
                else append(st, "⚠ 端口探活未通过（" + hl + "）：容器可能在重启循环或依赖未就绪，请上远端执行 docker logs " + cname + " 排查\n");
            }

            // 7.5 附带基础数据（withData）：等远端 meta 建表完成（表数达标）→ 上传 dump → 导入（重试兜底）→ 校验
            if (withData) {
                Path localDump = Paths.get(imageDir).resolve(dumpFile);
                // 期望表数 = dump 表数 + 排除的 3 张运行历史表（同为种子所建）= 本地 meta 精确总表数。
                // nginx 先于 java 起动，HTTP 探活通过 ≠ 建表完成；sys_user 又建得比 asset 早，必须按表数等齐。
                int markers = 0;
                for (String ln : Files.readAllLines(localDump, StandardCharsets.UTF_8))
                    if (ln.startsWith("-- 表 meta.")) markers++;
                int expectTabs = markers + DUMP_EXCLUDE.size();
                append(st, "\n[附带基础数据] 等待远端 meta 建表完成（期望 " + expectTabs + " 张，最多 ~3 分钟）...\n");
                String cntCmd = dockerBin + " exec pharma-starrocks mysql -h127.0.0.1 -P9030 -uroot -N -e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='meta'\"";
                String waitMeta = "i=0; while [ $i -lt 60 ]; do n=$(" + cntCmd + " 2>/dev/null); [ \"$n\" -ge " + expectTabs + " ] && echo CT_META_OK && break; i=$((i+1)); sleep 3; done";
                RemoteCmdExec.ExecResult wm = RemoteCmdExec.runCmd(c, sud + "sh -c " + shq(waitMeta), 220, pwd);
                if (wm.log == null || !wm.log.contains("CT_META_OK"))
                    append(st, "⚠ 表数未达期望（建表仍在进行或个别表创建失败），继续尝试导入...\n");
                String remoteDump = uploadDir + "/" + dumpFile;
                append(st, "上传基础数据 " + dumpFile + " (" + Files.size(localDump) + " bytes) -> " + host + ":" + remoteDump + " ...\n");
                try (InputStream din = Files.newInputStream(localDump)) {
                    RemoteCmdExec.uploadStream(c, remoteDump, din);
                }
                // 重定向放 sh -c 内：stdin 只走 sudo 密码，避免密码行混入 SQL；导入幂等（TRUNCATE+INSERT），重试兜底建表竞态
                String impInner = dockerBin + " exec -i pharma-starrocks mysql -h127.0.0.1 -P9030 -uroot < " + shq(remoteDump);
                append(st, "$ " + (sudoLoad ? "sudo " : "") + "sh -c \"docker exec -i pharma-starrocks mysql ... < " + remoteDump + "\"\n");
                RemoteCmdExec.ExecResult imp = null;
                for (int at = 1; at <= 3; at++) {
                    if (at > 1) {
                        append(st, "第 " + at + " 次导入（等待应用完成建表）...\n");
                        try { Thread.sleep(30000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    imp = RemoteCmdExec.runCmd(c, sud + "sh -c " + shq(impInner), 300, pwd);
                    if (imp.ok) break;
                    append(st, "  导入未成功: " + imp.err + "\n");
                }
                RemoteCmdExec.runCmd(c, sud + "rm -f " + shq(remoteDump), 30, pwd);   // 清理远端 dump（失败不阻塞）
                if (imp == null || !imp.ok) {
                    append(st, "⚠ 应用与大数据栈已在运行，仅基础数据同步失败，可修复后重新部署并勾选附带基础数据\n");
                    throw new RuntimeException("meta 基础数据导入失败: " + (imp == null ? "中断" : imp.err));
                }
                RemoteCmdExec.ExecResult uc = RemoteCmdExec.runCmd(c,
                        sud + dockerBin + " exec pharma-starrocks mysql -h127.0.0.1 -P9030 -uroot -N -e \"SELECT COUNT(*) FROM meta.sys_user\"", 30, pwd);
                append(st, "✓ meta 基础数据已同步（用户 " + (uc.log == null ? "?" : uc.log.trim()) + " 名，账号/菜单与本地一致）\n");
            }
            append(st, "\n✓ 部署成功" + (autoStart ? "并已启动" : "") + "：" + fullTag + " @ " + host + "\n");
            ok = true;
        } catch (Exception e) {
            err = rootMsg(e);
            append(st, "\n✗ " + err + "\n");
        } finally {
            try {
                jdbc.update("INSERT INTO meta.ct_deploy_record(id, version_id, server_id, status, log_text, start_time, end_time, error_msg, triggered_by, with_stack, with_data) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)", deployId, versionId, serverId, ok ? "SUCCESS" : "FAIL", st.log,
                        new Timestamp(start), new Timestamp(System.currentTimeMillis()), err, user,
                        withStack ? "ON" : "OFF", withData ? "ON" : "OFF");
            } catch (Exception ignored) {}
            if (dumpFile != null) { try { Files.deleteIfExists(Paths.get(imageDir).resolve(dumpFile)); } catch (Exception ignored) {} }
            st.status = ok ? "SUCCESS" : "FAIL";
            st.finishAt = System.currentTimeMillis();
        }
    }

    /** 6.5 附带大数据栈：上传 docker/ 编排资产 → 远端 compose up（远端自行拉镜像）→ 等 StarRocks 就绪 → 初始化数仓分层库。 */
    private void deployStack(RemoteCmdExec.Conn c, String sud, String pwd, String dockerBin, String uploadDir, LiveState st) {
        // a. compose 插件探测（v2 `docker compose` 优先，退回 v1 `docker-compose`）
        String probeInner = "docker compose version >/dev/null 2>&1 && echo CT_COMPOSE_OK || ( docker-compose version >/dev/null 2>&1 && echo CT_DC1_OK || echo CT_COMPOSE_NO )";
        RemoteCmdExec.ExecResult cp = RemoteCmdExec.runCmd(c, sud + "sh -c " + shq(probeInner), 30, pwd);
        String cpl = cp.log == null ? "" : cp.log;
        String compose;
        if (cpl.contains("CT_COMPOSE_OK")) compose = "docker compose";
        else if (cpl.contains("CT_DC1_OK")) compose = "docker-compose";
        else throw new RuntimeException("远端无 docker compose 插件（v2/v1 均未检出），无法附带大数据栈");
        append(st, "[附带大数据栈] compose 插件：" + compose + "\n");

        // b. 内存探测（仅警告）：栈 mem_limit 合计约 8.3GB
        RemoteCmdExec.ExecResult mi = RemoteCmdExec.runCmd(c, sud + dockerBin + " info --format '{{.MemTotal}}'", 30, pwd);
        long mem = lng(mi.log == null ? "" : mi.log.trim());
        if (mem > 0 && mem < 6L * 1024 * 1024 * 1024)
            append(st, "⚠ 远端内存 " + (mem / 1024 / 1024) + "MB < 6GB，大数据栈可能 OOM，建议扩内存\n");

        // c. 上传编排资产（compose 的 ./hop-jdbc、./hop-output bind 相对 compose 文件所在目录生效）
        String remoteBase = uploadDir + "/bigdata";
        if ("/tmp".equals(uploadDir)) append(st, "⚠ 部署目录不可写，栈编排文件落在 /tmp/bigdata（远端重启后丢失，已起容器不受影响）\n");
        RemoteCmdExec.ExecResult mk = RemoteCmdExec.runCmd(c,
                "mkdir -p " + shq(remoteBase + "/hop-jdbc") + " " + shq(remoteBase + "/init") + " "
                        + shq(remoteBase + "/hop-output") + " " + shq(remoteBase + "/iceberg-rest-aws"), 30);
        if (!mk.ok) throw new RuntimeException("远端目录创建失败: " + mk.err);
        Path dockerRoot = Paths.get(contextDir).resolve("docker");
        List<Path> files = new ArrayList<>();
        for (String rel : STACK_FILES) {
            Path p = dockerRoot.resolve(rel);
            if (Files.exists(p)) files.add(p);
            else append(st, "  跳过缺失资产 " + rel + "\n");
        }
        try (var walk = Files.walk(dockerRoot.resolve("hop-output"))) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        } catch (Exception ignored) {}
        for (Path p : files) {
            String rel = dockerRoot.relativize(p).toString().replace('\\', '/');
            try (InputStream in = Files.newInputStream(p)) {
                RemoteCmdExec.uploadStream(c, remoteBase + "/" + rel, in);
            } catch (Exception e) { throw new RuntimeException("上传 " + rel + " 失败: " + rootMsg(e)); }
            try { append(st, "  上传 bigdata/" + rel + " (" + Files.size(p) + " bytes)\n"); } catch (Exception ignored) {}
        }

        // d. Kafka 广播地址补丁（改远端副本，本地不动）：localhost → host.docker.internal，
        //    应用容器经 host-gateway 本地回连，不依赖云 NAT 回环、不暴露 9094 公网
        RemoteCmdExec.ExecResult sd = RemoteCmdExec.runCmd(c,
                "sed -i \"s|EXTERNAL://localhost:9094|EXTERNAL://host.docker.internal:9094|g\" " + shq(remoteBase + "/docker-compose.yml"), 30);
        if (sd.ok) append(st, "已将 Kafka EXTERNAL 广播地址改为 host.docker.internal:9094\n");
        else append(st, "⚠ Kafka 广播地址补丁未生效（不影响登录，实时接入可能需手工调整）: " + sd.err + "\n");

        // e. compose up（远端自行拉镜像，超时 30min；重复部署幂等——已有镜像不重拉；逐行流式滚屏）
        append(st, "$ " + (sud.isEmpty() ? "" : "sudo ") + compose + " -f " + remoteBase + "/docker-compose.yml up -d（远端拉取镜像，耗时视网络）\n");
        RemoteCmdExec.ExecResult up = RemoteCmdExec.runCmd(c,
                sud + compose + " -f " + shq(remoteBase + "/docker-compose.yml") + " up -d", 1800, pwd,
                line -> append(st, line + "\n"));
        if (!up.ok) throw new RuntimeException("远端 compose up 失败（常见原因：无法访问镜像仓库拉取，可在远端 /etc/docker/daemon.json 配置 registry-mirrors 后重试）: " + up.err);

        // f. 等 StarRocks 可查询（仿 bring-up.sh：容器内 mysql 客户端探 9030，60×3s）
        append(st, "等待远端 StarRocks 就绪...\n");
        String srq = dockerBin + " exec pharma-starrocks mysql -h127.0.0.1 -P9030 -uroot -e \"SELECT 1\" >/dev/null 2>&1";
        String waitSr = "i=0; while [ $i -lt 60 ]; do " + srq + " && echo CT_SR_OK && break; i=$((i+1)); sleep 3; done";
        RemoteCmdExec.ExecResult ws = RemoteCmdExec.runCmd(c, sud + "sh -c " + shq(waitSr), 220, pwd);
        if (ws.log == null || !ws.log.contains("CT_SR_OK"))
            throw new RuntimeException("远端 StarRocks 180s 未就绪，请上远端执行 docker logs pharma-starrocks 排查");
        append(st, "StarRocks 就绪\n");

        // g. 数仓分层库初始化（幂等 CREATE DATABASE IF NOT EXISTS；重定向放 sh -c 内，stdin 只走 sudo 密码）
        if (Files.exists(dockerRoot.resolve("init/doris-ddl.sql"))) {
            String ddlInner = dockerBin + " exec -i pharma-starrocks mysql -h127.0.0.1 -P9030 -uroot < " + shq(remoteBase + "/init/doris-ddl.sql");
            RemoteCmdExec.ExecResult dl = RemoteCmdExec.runCmd(c, sud + "sh -c " + shq(ddlInner), 120, pwd);
            if (dl.ok) append(st, "数仓分层库 ods/dwd/dws/ads/dim 已就绪\n");
            else append(st, "⚠ 数仓分层库初始化失败（不阻塞部署）: " + dl.err + "\n");
        }

        // g2. 湖仓初始化：① minio/mc 临时容器建 lake 桶（Iceberg warehouse）② SR 挂 Iceberg External Catalog。
        //     两者均幂等（已存在仅提示/报 already exists），失败不阻塞部署（湖功能后续可手工补）。
        String mcInit = dockerBin + " run --rm --network=pharma-bigdata_default " +
                "-e MC_HOST_pharma=http://minioadmin:minioadmin@minio:9000 " +
                "-v " + shq(remoteBase + "/init/minio-init.sh") + ":/minio-init.sh:ro " +
                "minio/mc:latest sh /minio-init.sh";
        RemoteCmdExec.ExecResult mb = RemoteCmdExec.runCmd(c, mcInit, 300);
        if (mb.ok) append(st, "MinIO 湖桶 lake 已就绪\n");
        else append(st, "⚠ MinIO 建桶失败（不阻塞部署，湖接入前需手工建 lake 桶）: " + mb.err + "\n");

        if (Files.exists(dockerRoot.resolve("init/iceberg-catalog.sql"))) {
            String catInner = dockerBin + " exec -i pharma-starrocks mysql -h127.0.0.1 -P9030 -uroot < " + shq(remoteBase + "/init/iceberg-catalog.sql");
            RemoteCmdExec.ExecResult cat = RemoteCmdExec.runCmd(c, sud + "sh -c " + shq(catInner), 120, pwd);
            // 幂等：重复部署报 already exists 也视为成功
            if (cat.ok || (cat.err != null && cat.err.contains("already exists")))
                append(st, "Iceberg External Catalog iceberg_catalog 已挂载\n");
            else append(st, "⚠ Iceberg Catalog 挂载失败（不阻塞部署）: " + cat.err + "\n");
        }
    }

    // ============ meta 基础数据逻辑导出（withData 用） ============
    /** 逻辑导出 meta 库（同步、秒级）：SHOW TABLES → 每表 TRUNCATE+批量 INSERT。返回 imageDir 下文件名。 */
    public String dumpMeta() {
        StringBuilder out = new StringBuilder();
        out.append("-- meta 逻辑导出 ").append(new Timestamp(System.currentTimeMillis()))
                .append("（排除运行历史表: ").append(String.join(", ", DUMP_EXCLUDE)).append("）\n");
        for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES FROM meta")) {
            String tn = str(t.values().iterator().next());
            if (DUMP_EXCLUDE.contains(tn)) continue;
            try { writeTableDump(tn, out); }
            catch (Exception e) { throw new RuntimeException("导出表 meta." + tn + " 失败: " + rootMsg(e)); }
        }
        try {
            Path dir = Paths.get(imageDir);
            Files.createDirectories(dir);
            String fn = "meta-data_" + System.currentTimeMillis() + ".sql";
            Files.write(dir.resolve(fn), out.toString().getBytes(StandardCharsets.UTF_8));
            return fn;
        } catch (Exception e) { throw new RuntimeException("写出导出文件失败: " + rootMsg(e)); }
    }

    /** 单表导出：TRUNCATE + 多行 VALUES 批量 INSERT（200 行/条）。 */
    private void writeTableDump(String table, StringBuilder out) {
        out.append("\n-- 表 meta.").append(table).append("\nTRUNCATE TABLE meta.").append(table).append(";\n");
        jdbc.query("SELECT * FROM meta." + table, (ResultSetExtractor<Void>) rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            String[] cols = new String[n];
            for (int i = 0; i < n; i++) cols[i] = md.getColumnName(i + 1);
            String head = "INSERT INTO meta." + table + " (" + String.join(",", cols) + ") VALUES ";
            StringBuilder ins = new StringBuilder(head);
            int batch = 0, total = 0;
            while (rs.next()) {
                if (batch > 0) ins.append(",");
                ins.append("(");
                for (int i = 0; i < n; i++) { if (i > 0) ins.append(","); ins.append(sqlLit(rs.getObject(i + 1))); }
                ins.append(")");
                if (++batch >= 200) { out.append(ins).append(";\n"); ins = new StringBuilder(head); batch = 0; }
                total++;
            }
            if (batch > 0) out.append(ins).append(";\n");
            out.append("-- ").append(total).append(" 行\n");
            return null;
        });
    }

    /** SQL 字面量：null/数值/布尔直出；其余按字符串转义（' → ''，反斜杠/换行/回车转 \\ \n \r）。 */
    private static String sqlLit(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder(s.length() + 2).append('\'');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') b.append("''");
            else if (ch == '\\') b.append("\\\\");
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else b.append(ch);
        }
        return b.append('\'').toString();
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
    /** 追加 live 日志并设上限（超 ~400KB 只留尾部，防超长部署把内存/轮询响应撑爆；历史表仍存完整 200KB 截断版）。 */
    private void append(LiveState st, String s) {
        if (st == null) return;
        st.log += s;
        if (st.log.length() > 400000) st.log = st.log.substring(st.log.length() - 400000);
    }
    /** 远端 shell 单引号包裹（内部 ' 转义为 '\'')，供 -e KEY=VALUE 等含特殊字符参数。 */
    private static String shq(String v) { return "'" + v.replace("'", "'\\''") + "'"; }
    /** 端口合法性：1-65535 数字，非法/为空回退 80。 */
    private static String portOf(Object o) {
        try { int p = Integer.parseInt(str(o).trim()); return (p > 0 && p < 65536) ? String.valueOf(p) : "80"; }
        catch (Exception e) { return "80"; }
    }
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
