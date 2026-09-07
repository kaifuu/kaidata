package com.pharma.service.access.container;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 远端 SSH 命令执行（ChannelExec）+ 流式 SFTP 上传（ChannelSftp.put(InputStream)）。
 * <p>
 * session 构建仿 SftpFileClient（StrictHostKeyChecking=no / mwiede fork 兼容 com.jcraft.jsch）。
 * 大文件（镜像 tar，GB 级）必须用 uploadStream 流式，不可走 byte[]（OOM）。
 */
public final class RemoteCmdExec {

    public static class Conn {
        public final String host; public final int port; public final String user; public final String pwd;
        /** 认证方式：PASSWORD（默认，pwd 生效）/ KEY（privateKey PEM 文本生效，keyPassphrase 为私钥口令） */
        public final String authType;
        public final String privateKey;
        public final String keyPassphrase;
        public Conn(String host, int port, String user, String pwd) {
            this(host, port, user, pwd, "PASSWORD", "", "");
        }
        public Conn(String host, int port, String user, String pwd, String authType, String privateKey, String keyPassphrase) {
            this.host = host; this.port = port; this.user = user; this.pwd = pwd;
            this.authType = authType == null ? "PASSWORD" : authType;
            this.privateKey = privateKey == null ? "" : privateKey;
            this.keyPassphrase = keyPassphrase == null ? "" : keyPassphrase;
        }
    }

    public static class ExecResult {
        public boolean ok;
        public String log = "";
        public String err = "";
    }

    /** 测试 SSH 连通（握手 + echo ok），msg 携带认证策略诊断说明。 */
    public static Map<String, Object> test(Conn c) {
        StringBuilder notes = new StringBuilder();
        ExecResult r = exec(c, "echo ok", 15, null, null, notes::append);
        if (r.ok) {
            String n = notes.toString();
            return Map.of("ok", true, "msg", n.isEmpty() ? "SSH 连通成功" : "SSH 连通成功（" + n + "）");
        }
        return Map.of("ok", false, "msg", friendly(r.err));
    }

    /** 执行远端命令，捕获 stdout（log）/stderr（err）。 */
    public static ExecResult runCmd(Conn c, String cmd, int timeoutSec) {
        return exec(c, cmd, timeoutSec, null, null, null);
    }

    /**
     * 执行远端命令并向其 stdin 注入一段数据（如 sudo -S 的密码）。
     * <p>密码走 stdin 而非拼进命令行，避免出现在远端 ps/sh 历史。
     */
    public static ExecResult runCmd(Conn c, String cmd, int timeoutSec, String stdinData) {
        return exec(c, cmd, timeoutSec, stdinData, null, null);
    }

    /**
     * 执行远端命令并按行流式回调 stdout（onLine 每收到一行即触发，行尾 \r\n 已剥离）。
     * <p>用于部署等长命令的实时日志滚屏——原实现命令结束后才整体返回，30 分钟的
     * docker load 期间前端日志零增量。回调在 IO 线程同步执行，应只做轻量追加。
     */
    public static ExecResult runCmd(Conn c, String cmd, int timeoutSec, String stdinData, java.util.function.Consumer<String> onLine) {
        return exec(c, cmd, timeoutSec, stdinData, onLine, null);
    }

    /** 流式上传大文件：ChannelSftp.put(InputStream, remotePath)（默认 OVERWRITE，分块传输不进内存）。 */
    public static void uploadStream(Conn c, String remotePath, InputStream in) {
        Session s = null; ChannelSftp ch = null;
        try {
            s = newSession(c, null);
            ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(15000);
            ch.put(in, remotePath);
        } catch (Exception e) {
            throw new RuntimeException("SFTP 上传失败: " + rootMsg(e), e);
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Exception ignored) {}
            if (s != null) try { s.disconnect(); } catch (Exception ignored) {}
        }
    }

    // ---- 私有 ----

    /**
     * 建连接。KEY 认证内置兜底策略：① 按所存口令失败且口令非空 → 自动按无口令重试
     * （常见误配：私钥本无口令却保存了口令，私钥无法解密等同未提供公钥）；② 公钥被拒
     * 且配了密码 → 回退密码认证。onNote 回调人话说明（测试连接 msg / 部署日志展示）。
     * 认证被拒时抛 IllegalArgumentException，消息带逐项排查指引。
     */
    private static Session newSession(Conn c, java.util.function.Consumer<String> onNote) throws Exception {
        if ("KEY".equals(c.authType) && !c.privateKey.isEmpty()) {
            String k = c.privateKey.trim();
            if (!k.contains("-----BEGIN") && !k.startsWith("PuTTY-User-Key-File"))
                throw new IllegalArgumentException("私钥内容无效：缺少 -----BEGIN ... PRIVATE KEY----- 文件头（也不像 PuTTY .ppk），请重新上传/粘贴私钥文件");
            // 私钥文件本身是否带口令加密（PEM 的 ENCRYPTED/Proc-Type、OpenSSH 格式的 bcrypt KDF）
            boolean keyEncrypted = k.contains("ENCRYPTED") || k.contains("Proc-Type") || k.contains("bcrypt");
            Exception e1 = null, e2 = null;
            try {
                return connectKey(c, c.keyPassphrase);
            } catch (Exception e) {
                if (!authRejected(e)) throw e;   // 网络/超时类错误换策略重试无意义
                e1 = e;
            }
            if (!c.keyPassphrase.isEmpty()) {
                try {
                    Session s = connectKey(c, "");
                    if (onNote != null) onNote.accept("私钥实际无口令，已自动忽略所存口令并连接成功，建议清空「私钥口令」字段");
                    return s;
                } catch (Exception e) {
                    if (!authRejected(e)) throw e;
                    e2 = e;
                }
            }
            if (!c.pwd.isEmpty()) {
                try {
                    Session s = connectPwd(c);
                    if (onNote != null) onNote.accept("公钥认证被拒，已回退密码认证成功，建议检查公钥配置");
                    return s;
                } catch (Exception ignored) { }
            }
            String tries = "已尝试：公钥(带所存口令) → " + rootMsg(e1)
                    + (e2 == null ? "" : "；公钥(无口令重试) → " + rootMsg(e2));
            String encHint = keyEncrypted
                    ? "该私钥文件本身带口令加密，「私钥口令」必须与生成私钥时设置的完全一致"
                    : "该私钥文件本身未加密——「私钥口令」应留空；若留空仍失败，多为公钥未部署到远端";
            throw new IllegalArgumentException("SSH 认证失败：服务器拒绝公钥认证。" + encHint
                    + "。请逐项检查：① 公钥是否已加入远端用户 " + c.user + " 的 ~/.ssh/authorized_keys；"
                    + "② 私钥口令（当前" + (c.keyPassphrase.isEmpty() ? "未配置" : "已配置") + "）；"
                    + "③ 用户名/端口（当前 " + c.user + "@" + c.host + ":" + c.port + "）。"
                    + tries);
        }
        if (c.pwd == null || c.pwd.isEmpty())
            throw new IllegalArgumentException("认证方式为密码，但未配置登录密码");
        return connectPwd(c);
    }

    /** 是否认证被拒（可换策略重试；网络/超时/解析类错误重试无意义）。大小写不敏感：mwiede fork 报 "USERAUTH fail"，标准报 "Auth fail"。 */
    private static boolean authRejected(Exception e) {
        String m = rootMsg(e).toLowerCase();
        return m.contains("auth fail") || m.contains("auth cancel") || m.contains("userauth")
                || m.contains("invalid privatekey") || m.contains("failed to decrypt");
    }

    private static Session connectKey(Conn c, String passphrase) throws Exception {
        JSch jsch = new JSch();
        Session s = jsch.getSession(c.user, c.host, c.port);
        try {
            // 秘钥文件认证：PEM 文本以字节数组注入（免临时文件）；口令空串按无口令处理
            byte[] phrase = passphrase == null || passphrase.isEmpty() ? null : passphrase.getBytes(StandardCharsets.UTF_8);
            jsch.addIdentity("ct-key", c.privateKey.getBytes(StandardCharsets.UTF_8), null, phrase);
            s.setConfig("StrictHostKeyChecking", "no");
            s.connect(15000);
            return s;
        } catch (Exception e) {
            try { s.disconnect(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private static Session connectPwd(Conn c) throws Exception {
        JSch jsch = new JSch();
        Session s = jsch.getSession(c.user, c.host, c.port);
        try {
            s.setPassword(c.pwd);
            s.setConfig("StrictHostKeyChecking", "no");
            s.connect(15000);
            return s;
        } catch (Exception e) {
            try { s.disconnect(); } catch (Exception ignored) {}
            if (authRejected(e))
                throw new IllegalArgumentException("SSH 密码认证被拒：检查用户名/密码（当前 " + c.user + "@" + c.host + ":" + c.port + "）。原始错误：" + rootMsg(e));
            throw e;
        }
    }

    /** 常见连接错误翻译成人话；认证类指引已由 newSession 抛出的 IllegalArgumentException 携带。 */
    private static String friendly(String err) {
        if (err == null || err.isEmpty()) return "连接失败";
        String e = err.replaceFirst("^IllegalArgumentException: ", "");
        String low = e.toLowerCase();
        if (low.contains("timed out") || low.contains("timeout")) return "连接超时：检查网络可达性与防火墙/安全组放行。原始错误：" + e;
        if (low.contains("connection refused")) return "连接被拒绝：端口未开放或 SSH 服务未运行。原始错误：" + e;
        if (low.contains("unknownhost")) return "主机名解析失败：检查地址拼写/DNS。原始错误：" + e;
        return e;
    }

    private static ExecResult exec(Conn c, String cmd, int timeoutSec, String stdinData, java.util.function.Consumer<String> onLine, java.util.function.Consumer<String> onNote) {
        Session s = null; ChannelExec ch = null;
        ExecResult r = new ExecResult();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream errb = new ByteArrayOutputStream();
        // 逐行流式回调的行缓冲（按整行一次性 UTF-8 解码，天然规避多字节字符被读拆断）
        ByteArrayOutputStream lineBuf = onLine == null ? null : new ByteArrayOutputStream();
        try {
            s = newSession(c, onNote);
            ch = (ChannelExec) s.openChannel("exec");
            ch.setCommand(cmd);
            ch.setInputStream(stdinData == null ? null
                    : new java.io.ByteArrayInputStream((stdinData + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.setErrStream(errb, false);
            InputStream in = ch.getInputStream();
            ch.connect(15000);
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(buf);
                    if (i < 0) break;
                    if (out.size() < 200000) out.write(buf, 0, i);
                    if (lineBuf != null) emitLines(lineBuf, onLine, buf, i);
                }
                if (ch.isClosed()) {
                    while (in.available() > 0) { int i = in.read(buf); if (i < 0) break; if (out.size() < 200000) out.write(buf, 0, i); if (lineBuf != null) emitLines(lineBuf, onLine, buf, i); }
                    break;
                }
                if (System.currentTimeMillis() > deadline) { r.err = "远端命令超时（" + timeoutSec + "s）"; break; }
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            // 收尾：无换行结尾的残行也回调出去
            if (lineBuf != null && lineBuf.size() > 0) {
                onLine.accept(lineBuf.toString(StandardCharsets.UTF_8).stripTrailing());
                lineBuf.reset();
            }
            r.log = out.toString(StandardCharsets.UTF_8);
            String e = errb.toString(StandardCharsets.UTF_8);
            if (e != null && !e.isEmpty()) r.err = (r.err == null || r.err.isEmpty()) ? e : r.err + "\n" + e;
            r.ok = ch.isClosed() && ch.getExitStatus() == 0;
            if (!r.ok && (r.err == null || r.err.isEmpty())) {
                r.err = ch.isClosed() ? ("退出码 " + ch.getExitStatus()) : "远端命令超时或未正常结束";
            }
        } catch (Exception ex) {
            r.ok = false;
            if (r.err == null || r.err.isEmpty()) r.err = rootMsg(ex);
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Exception ignored) {}
            if (s != null) try { s.disconnect(); } catch (Exception ignored) {}
        }
        return r;
    }

    /** 把本次读到的 buf[0..len) 按 \n 切行回调（\r 剥离），残行留在 lineBuf 等下一批。 */
    private static void emitLines(ByteArrayOutputStream lineBuf, java.util.function.Consumer<String> onLine, byte[] buf, int len) {
        for (int k = 0; k < len; k++) {
            byte b = buf[k];
            if (b == '\n') {
                String ln = lineBuf.toString(StandardCharsets.UTF_8);
                lineBuf.reset();
                if (ln.endsWith("\r")) ln = ln.substring(0, ln.length() - 1);
                onLine.accept(ln);
            } else {
                lineBuf.write(b);
            }
        }
    }

    private static String rootMsg(Throwable e) {
        Throwable c = e;
        for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause();
        if (c.getMessage() == null) return c.getClass().getSimpleName();
        // IllegalArgumentException 是本类的人话说明通道（认证失败排查指引等），不再附类名前缀
        if (c instanceof IllegalArgumentException) return c.getMessage();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private RemoteCmdExec() {}
}
