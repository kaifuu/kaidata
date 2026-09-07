package com.pharma.service.controller;

import com.pharma.service.access.container.ContainerBuildExecutor;
import com.pharma.service.access.container.DockerCli;
import com.pharma.service.access.container.RemoteCmdExec;
import com.pharma.service.access.util.CryptoUtil;
import com.pharma.service.security.Authz;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 容器管理 [SYS_ADMIN]：镜像版本构建/下载 + 远端服务器 + 部署到远端 CentOS/Linux。 */
@RestController
@RequestMapping("/api/container")
@CrossOrigin(origins = "*")
public class ContainerController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DockerCli docker;
    @Autowired private ContainerBuildExecutor exec;
    @Autowired private CryptoUtil crypto;
    @Value("${pharma.image.dir:data/images}") private String imageDir;
    @Value("${pharma.image.dockerfile:docker/app/Dockerfile}") private String dockerfilePath;

    /** 下载票据：ticket -> [versionId, expireMs]，一次性消费（window.open 不带 token 头，故先换 ticket）。 */
    private final Map<String, long[]> tickets = new ConcurrentHashMap<>();

    // ===== docker 探测 =====
    @GetMapping("/docker/info")
    public Map<String, Object> dockerInfo() {
        Authz.require(Authz.SYS_ADMIN);
        return Map.of("available", docker.available(), "info", docker.info());
    }

    // ===== 镜像版本 CRUD =====
    @GetMapping("/version/list")
    public List<Map<String, Object>> versionList(@RequestParam(required = false) String kw,
                                                 @RequestParam(required = false) String status) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT id,name,tag,version_label,image_id,size_bytes,expose_port,status,file_path,file_size,remark,create_by,create_time FROM meta.ct_image_version WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) { sql.append(" AND status=?"); args.add(status); }
        if (kw != null && !kw.isEmpty()) {
            sql.append(" AND (name LIKE ? OR tag LIKE ? OR version_label LIKE ?)");
            String p = "%" + kw + "%"; args.add(p); args.add(p); args.add(p);
        }
        sql.append(" ORDER BY id DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/version/detail")
    public Map<String, Object> versionDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> v = jdbc.queryForMap("SELECT id,name,tag,version_label,image_id,size_bytes,dockerfile_text,expose_port,status,file_path,file_size,remark,create_by,create_time FROM meta.ct_image_version WHERE id=?", id);
        v.put("buildRuns", jdbc.queryForList("SELECT id,action,status,start_time,end_time,error_msg,triggered_by FROM meta.ct_image_build_run WHERE version_id=? ORDER BY id DESC LIMIT 50", id));
        v.put("deploys", jdbc.queryForList("SELECT d.id,d.version_id,d.server_id,d.status,d.start_time,d.end_time,d.triggered_by,s.name AS server_name FROM meta.ct_deploy_record d LEFT JOIN meta.ct_server s ON s.id=d.server_id WHERE d.version_id=? ORDER BY d.id DESC LIMIT 50", id));
        return v;
    }

    @PostMapping("/version")
    public Map<String, Object> createVersion(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        Timestamp now = new Timestamp(id);
        jdbc.update("INSERT INTO meta.ct_image_version(id,name,tag,version_label,image_id,size_bytes,dockerfile_text,expose_port,status,file_path,file_size,remark,create_by,create_time,update_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.get("tag")), str(b.get("version_label")),
                "", 0L, readDockerfile(), str(b.getOrDefault("expose_port", "80")),
                "DRAFT", "", 0L, str(b.get("remark")), currentUser(), now, now);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/version")
    public Map<String, Object> updateVersion(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.ct_image_version SET name=?,tag=?,version_label=?,expose_port=?,remark=?,update_time=? WHERE id=?",
                str(b.get("name")), str(b.get("tag")), str(b.get("version_label")), str(b.get("expose_port")),
                str(b.get("remark")), new Timestamp(System.currentTimeMillis()), lng(b.get("id")));
        return Map.of("success", true);
    }

    @DeleteMapping("/version")
    public Map<String, Object> deleteVersion(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> v = jdbc.queryForMap("SELECT file_path FROM meta.ct_image_version WHERE id=?", id);
        String fp = str(v.get("file_path"));
        if (!fp.isEmpty()) {
            try { Files.deleteIfExists(Paths.get(imageDir).resolve(fp)); } catch (Exception ignored) {}
        }
        jdbc.update("DELETE FROM meta.ct_image_build_run WHERE version_id=?", id);
        jdbc.update("DELETE FROM meta.ct_deploy_record WHERE version_id=?", id);
        jdbc.update("DELETE FROM meta.ct_image_version WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 构建（异步） =====
    @PostMapping("/version/build")
    public Map<String, Object> build(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        if (!docker.available()) throw new com.pharma.service.security.AccessDeniedException("本机 docker 不可用，无法构建镜像");
        jdbc.update("UPDATE meta.ct_image_version SET status='BUILT', update_time=? WHERE id=?",
                new Timestamp(System.currentTimeMillis()), id);
        long runId = exec.submitBuild(id, currentUser());
        return Map.of("success", true, "runId", runId);
    }

    @GetMapping("/version/build-status")
    public Map<String, Object> buildStatus(@RequestParam long versionId) {
        Authz.require(Authz.SYS_ADMIN);
        ContainerBuildExecutor.LiveState st = exec.liveStatus(versionId);
        if (st == null) return Map.of("status", "NONE", "log", "");
        return Map.of("status", st.status, "log", st.log, "runId", st.runId);
    }

    // ===== 下载 tar（票据 + 流式） =====
    @PostMapping("/version/download-ticket")
    public Map<String, Object> downloadTicket(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        String tk = UUID.randomUUID().toString().replace("-", "");
        tickets.put(tk, new long[]{id, System.currentTimeMillis() + 60000});
        return Map.of("success", true, "ticket", tk);
    }

    @GetMapping("/version/download")
    public void download(@RequestParam long id, @RequestParam String ticket, HttpServletResponse resp) throws IOException {
        Authz.require(Authz.SYS_ADMIN);
        long[] t = tickets.remove(ticket);
        if (t == null || t[0] != id || System.currentTimeMillis() > t[1]) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "下载票据无效或已过期");
            return;
        }
        Map<String, Object> v = jdbc.queryForMap("SELECT name,tag,file_path FROM meta.ct_image_version WHERE id=?", id);
        String fp = str(v.get("file_path"));
        if (fp.isEmpty()) { resp.sendError(HttpServletResponse.SC_NOT_FOUND, "镜像尚未构建保存"); return; }
        Path tar = Paths.get(imageDir).resolve(fp);
        if (!Files.exists(tar)) { resp.sendError(HttpServletResponse.SC_NOT_FOUND, "tar 文件不存在"); return; }
        String filename = URLEncoder.encode(fp, StandardCharsets.UTF_8).replace("+", "%20");
        resp.setContentType("application/octet-stream");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        resp.setContentLengthLong(Files.size(tar));
        try (InputStream in = Files.newInputStream(tar); OutputStream out = resp.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    // ===== 构建历史 =====
    @GetMapping("/build-run/list")
    public List<Map<String, Object>> buildRuns(@RequestParam long versionId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id,version_id,action,status,log_text,start_time,end_time,error_msg,triggered_by FROM meta.ct_image_build_run WHERE version_id=? ORDER BY id DESC", versionId);
    }

    /** 打包历史（全局，跨版本）：轻列不含 log_text，日志点详情查看。 */
    @GetMapping("/build-run/all")
    public List<Map<String, Object>> buildRunAll(@RequestParam(required = false) String kw,
                                                 @RequestParam(required = false) String status) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder(
                "SELECT r.id,r.version_id,r.action,r.status,r.start_time,r.end_time,r.error_msg,r.triggered_by," +
                "v.name AS image_name,v.tag FROM meta.ct_image_build_run r LEFT JOIN meta.ct_image_version v ON v.id=r.version_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) { sql.append(" AND r.status=?"); args.add(status); }
        if (kw != null && !kw.isEmpty()) {
            sql.append(" AND (v.name LIKE ? OR v.tag LIKE ? OR v.version_label LIKE ? OR CAST(r.version_id AS VARCHAR(20)) LIKE ?)");
            String p = "%" + kw + "%"; args.add(p); args.add(p); args.add(p); args.add(p);
        }
        sql.append(" ORDER BY r.id DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 打包历史单条详情（含完整日志）。 */
    @GetMapping("/build-run/detail")
    public Map<String, Object> buildRunDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT r.id,r.version_id,r.action,r.status,r.log_text,r.start_time,r.end_time,r.error_msg,r.triggered_by," +
                "v.name AS image_name,v.tag FROM meta.ct_image_build_run r LEFT JOIN meta.ct_image_version v ON v.id=r.version_id WHERE r.id=?", id);
    }

    // ===== 发布目标（远端服务器） CRUD：密码 / 秘钥文件 双认证 =====
    @GetMapping("/server/list")
    public List<Map<String, Object>> serverList() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,name,host,ssh_port,username,password,auth_type," +
                "CASE WHEN private_key IS NULL OR private_key='' THEN 0 ELSE 1 END AS has_key," +
                "CASE WHEN key_passphrase IS NULL OR key_passphrase='' THEN 0 ELSE 1 END AS has_passphrase," +
                "CASE WHEN sudo_password IS NULL OR sudo_password='' THEN 0 ELSE 1 END AS has_sudo_pwd," +
                "use_sudo,auto_start,run_port,container_name,run_env,deploy_path,docker_bin,status,remark,create_time FROM meta.ct_server ORDER BY id DESC");
        rows.forEach(r -> { if (!str(r.get("password")).isEmpty()) r.put("password", "***"); });
        return rows;
    }

    /** 发布目标详情：密文四项解密回显（仅编辑抽屉用，SYS_ADMIN 鉴权；列表仍掩码）。 */
    @GetMapping("/server/detail")
    public Map<String, Object> serverDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> s = jdbc.queryForMap(
                "SELECT id,name,host,ssh_port,username,password,auth_type,private_key,key_passphrase,use_sudo,sudo_password," +
                "auto_start,run_port,container_name,run_env,deploy_path,docker_bin,status,remark FROM meta.ct_server WHERE id=?", id);
        s.put("password", crypto.decrypt(str(s.get("password"))));
        s.put("private_key", crypto.decrypt(str(s.get("private_key"))));
        s.put("key_passphrase", crypto.decrypt(str(s.get("key_passphrase"))));
        s.put("sudo_password", crypto.decrypt(str(s.get("sudo_password"))));
        return s;
    }

    @PostMapping("/server")
    public Map<String, Object> createServer(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        Timestamp now = new Timestamp(id);
        jdbc.update("INSERT INTO meta.ct_server(id,name,host,ssh_port,username,password,auth_type,private_key,key_passphrase,use_sudo,sudo_password,auto_start,run_port,container_name,run_env,deploy_path,docker_bin,status,remark,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.get("host")), (int) lng(b.get("ssh_port")), str(b.get("username")),
                crypto.encrypt(str(b.get("password"))), str(b.getOrDefault("auth_type", "PASSWORD")),
                crypto.encrypt(str(b.get("private_key"))), crypto.encrypt(str(b.get("key_passphrase"))),
                "ON".equals(str(b.get("use_sudo"))) ? "ON" : "OFF", crypto.encrypt(str(b.get("sudo_password"))),
                "ON".equals(str(b.get("auto_start"))) ? "ON" : "OFF", (int) lng(b.getOrDefault("run_port", 80)),
                str(b.get("container_name")), str(b.get("run_env")),
                str(b.getOrDefault("deploy_path", "/opt/images")),
                str(b.getOrDefault("docker_bin", "docker")), str(b.getOrDefault("status", "NORMAL")),
                str(b.get("remark")), now, now);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/server")
    public Map<String, Object> updateServer(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        Timestamp now = new Timestamp(System.currentTimeMillis());
        // 密文四项（密码/私钥/私钥口令/sudo密码）：编辑页回显解密明文——
        // 值为 *** = 沿用已存（兼容列表行测试等旧入口）；字段已提供且非 ***（含空串）= 更新（空串即清除）；
        // 字段未提供（null）= 不修改
        String authType = str(b.getOrDefault("auth_type", "PASSWORD"));
        if (authType.isEmpty()) authType = "PASSWORD";
        if (secretProvided(b.get("password")))
            jdbc.update("UPDATE meta.ct_server SET password=? WHERE id=?", crypto.encrypt(str(b.get("password"))), id);
        if (secretProvided(b.get("private_key")))
            jdbc.update("UPDATE meta.ct_server SET private_key=? WHERE id=?", crypto.encrypt(str(b.get("private_key"))), id);
        if (secretProvided(b.get("key_passphrase")))
            jdbc.update("UPDATE meta.ct_server SET key_passphrase=? WHERE id=?", crypto.encrypt(str(b.get("key_passphrase"))), id);
        if (secretProvided(b.get("sudo_password")))
            jdbc.update("UPDATE meta.ct_server SET sudo_password=? WHERE id=?", crypto.encrypt(str(b.get("sudo_password"))), id);
        jdbc.update("UPDATE meta.ct_server SET name=?,host=?,ssh_port=?,username=?,auth_type=?,use_sudo=?,auto_start=?,run_port=?,container_name=?,run_env=?,deploy_path=?,docker_bin=?,status=?,remark=?,update_time=? WHERE id=?",
                str(b.get("name")), str(b.get("host")), (int) lng(b.get("ssh_port")), str(b.get("username")),
                authType, "ON".equals(str(b.get("use_sudo"))) ? "ON" : "OFF",
                "ON".equals(str(b.get("auto_start"))) ? "ON" : "OFF", (int) lng(b.getOrDefault("run_port", 80)),
                str(b.get("container_name")), str(b.get("run_env")),
                str(b.get("deploy_path")), str(b.get("docker_bin")),
                str(b.get("status")), str(b.get("remark")), now, id);
        return Map.of("success", true);
    }

    @DeleteMapping("/server")
    public Map<String, Object> deleteServer(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.ct_server WHERE id=?", id);
        return Map.of("success", true);
    }

    @PostMapping("/server/test")
    public Map<String, Object> serverTest(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        RemoteCmdExec.Conn c;
        if (b.get("id") != null && lng(b.get("id")) > 0 && "***".equals(str(b.get("private_key")))) {
            // 已保存目标：用库中密文解密（表单密文字段回显 *** 时）
            Map<String, Object> s = jdbc.queryForMap(
                    "SELECT host,ssh_port,username,password,auth_type,private_key,key_passphrase FROM meta.ct_server WHERE id=?", lng(b.get("id")));
            c = new RemoteCmdExec.Conn(str(s.get("host")), (int) lng(s.get("ssh_port")), str(s.get("username")),
                    crypto.decrypt(str(s.get("password"))), str(s.get("auth_type")),
                    crypto.decrypt(str(s.get("private_key"))), crypto.decrypt(str(s.get("key_passphrase"))));
        } else {
            // 未保存表单直测：秘钥/口令用明文
            c = new RemoteCmdExec.Conn(str(b.get("host")), (int) lng(b.get("ssh_port")), str(b.get("username")),
                    str(b.get("password")), str(b.getOrDefault("auth_type", "PASSWORD")),
                    str(b.get("private_key")), str(b.get("key_passphrase")));
        }
        Map<String, Object> r = RemoteCmdExec.test(c);
        // 开启 sudo 时连带预检 sudo 密码（sudo -S -v 仅验证凭据不执行命令；避免上传大 tar 后才发现密码错）
        if (Boolean.TRUE.equals(r.get("ok")) && "ON".equals(str(b.get("use_sudo")))) {
            String sudoPwd;
            if (b.get("id") != null && lng(b.get("id")) > 0 && "***".equals(str(b.get("sudo_password")))) {
                try {
                    Map<String, Object> s2 = jdbc.queryForMap("SELECT sudo_password FROM meta.ct_server WHERE id=?", lng(b.get("id")));
                    sudoPwd = crypto.decrypt(str(s2.get("sudo_password")));
                } catch (Exception e) { sudoPwd = ""; }
            } else {
                sudoPwd = str(b.get("sudo_password"));
            }
            if (sudoPwd.isEmpty()) return Map.of("ok", true, "msg", "SSH 连通成功（sudo 已开启但密码未配置）");
            RemoteCmdExec.ExecResult sv = RemoteCmdExec.runCmd(c, "sudo -S -p '' -v", 15, sudoPwd);
            if (!sv.ok) return Map.of("ok", false, "msg", "SSH 连通，但 sudo 验证失败（检查 sudo 密码/权限）");
            return Map.of("ok", true, "msg", "SSH 连通成功，sudo 提权可用");
        }
        return r;
    }

    // ===== 部署 =====
    @PostMapping("/deploy")
    public Map<String, Object> deploy(@RequestParam long versionId, @RequestParam long serverId,
                                      @RequestParam(required = false, defaultValue = "false") boolean withStack,
                                      @RequestParam(required = false, defaultValue = "false") boolean withData) {
        Authz.require(Authz.SYS_ADMIN);
        if (withData && !withStack)
            throw new com.pharma.service.security.AccessDeniedException("附带基础数据需同时勾选附带大数据栈（数据要导入远端 StarRocks）");
        Map<String, Object> v = jdbc.queryForMap("SELECT status,file_path FROM meta.ct_image_version WHERE id=?", versionId);
        if (!"SAVED".equals(str(v.get("status"))) || str(v.get("file_path")).isEmpty())
            throw new com.pharma.service.security.AccessDeniedException("镜像尚未构建保存，无法部署");
        String dumpFile = null;
        if (withData) {
            // 基础数据在应用建好 meta 库后导入，因此要求目标开启自动启动
            Map<String, Object> sr = jdbc.queryForMap("SELECT auto_start FROM meta.ct_server WHERE id=?", serverId);
            if (!"ON".equals(str(sr.get("auto_start"))))
                throw new com.pharma.service.security.AccessDeniedException("附带基础数据要求发布目标开启自动启动（应用先启动建好 meta 库才能导入）");
            dumpFile = exec.dumpMeta();   // 同步导出，失败立即反馈（尚未提交任务）
        }
        long deployId = exec.submitDeploy(versionId, serverId, currentUser(), withStack, withData, dumpFile);
        return Map.of("success", true, "deployId", deployId);
    }

    @GetMapping("/deploy/status")
    public Map<String, Object> deployStatus(@RequestParam long deployId) {
        Authz.require(Authz.SYS_ADMIN);
        ContainerBuildExecutor.LiveState st = exec.liveStatus(deployId);
        if (st == null) return Map.of("status", "NONE", "log", "");
        return Map.of("status", st.status, "log", st.log);
    }

    /** 部署记录详情（含完整日志）：RUNNING 时合并内存 live 态——刷新/重开窗口后重连滚屏。 */
    @GetMapping("/deploy/detail")
    public Map<String, Object> deployDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT d.id,d.version_id,d.server_id,d.status,d.log_text,d.start_time,d.end_time,d.error_msg,d.triggered_by,d.with_stack,d.with_data," +
                "v.name AS image_name,v.tag,s.name AS server_name FROM meta.ct_deploy_record d " +
                "LEFT JOIN meta.ct_image_version v ON v.id=d.version_id LEFT JOIN meta.ct_server s ON s.id=d.server_id WHERE d.id=?", id);
        ContainerBuildExecutor.LiveState st = exec.liveStatus(id);
        if (st != null) { row.put("status", st.status); row.put("log_text", st.log); }
        return row;
    }

    @GetMapping("/deploy/list")
    public List<Map<String, Object>> deployList(@RequestParam(required = false) Long versionId,
                                                @RequestParam(required = false) Long serverId) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT d.id,d.version_id,d.server_id,d.status,d.start_time,d.end_time,d.error_msg,d.triggered_by,d.with_stack,d.with_data,v.name AS image_name,v.tag,s.name AS server_name FROM meta.ct_deploy_record d LEFT JOIN meta.ct_image_version v ON v.id=d.version_id LEFT JOIN meta.ct_server s ON s.id=d.server_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (versionId != null) { sql.append(" AND d.version_id=?"); args.add(versionId); }
        if (serverId != null) { sql.append(" AND d.server_id=?"); args.add(serverId); }
        sql.append(" ORDER BY d.id DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---- 助手 ----
    /** 密文类字段是否按提交值更新：字段已提供（非 null，空串=清除）且不是掩码 ***。 */
    private static boolean secretProvided(Object v) {
        return v != null && !"***".equals(String.valueOf(v));
    }
    private String readDockerfile() {
        try { return Files.readString(Paths.get(dockerfilePath)); }
        catch (Exception e) { return ""; }
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
    private static String currentUser() {
        try { return com.pharma.service.security.AuthContext.username(); }
        catch (Exception e) { return "system"; }
    }
}
