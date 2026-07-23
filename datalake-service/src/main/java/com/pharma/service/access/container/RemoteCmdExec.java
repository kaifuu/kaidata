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
        public Conn(String host, int port, String user, String pwd) { this.host = host; this.port = port; this.user = user; this.pwd = pwd; }
    }

    public static class ExecResult {
        public boolean ok;
        public String log = "";
        public String err = "";
    }

    /** 测试 SSH 连通（握手 + echo ok）。 */
    public static Map<String, Object> test(Conn c) {
        ExecResult r = exec(c, "echo ok", 15);
        if (r.ok) return Map.of("ok", true, "msg", "SSH 连通成功");
        return Map.of("ok", false, "msg", (r.err == null || r.err.isEmpty()) ? "连接失败" : r.err);
    }

    /** 执行远端命令，捕获 stdout（log）/stderr（err）。 */
    public static ExecResult runCmd(Conn c, String cmd, int timeoutSec) {
        return exec(c, cmd, timeoutSec);
    }

    /** 流式上传大文件：ChannelSftp.put(InputStream, remotePath)（默认 OVERWRITE，分块传输不进内存）。 */
    public static void uploadStream(Conn c, String remotePath, InputStream in) {
        Session s = null; ChannelSftp ch = null;
        try {
            s = newSession(c);
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
    private static Session newSession(Conn c) throws Exception {
        JSch jsch = new JSch();
        Session s = jsch.getSession(c.user, c.host, c.port);
        s.setPassword(c.pwd);
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(15000);
        return s;
    }

    private static ExecResult exec(Conn c, String cmd, int timeoutSec) {
        Session s = null; ChannelExec ch = null;
        ExecResult r = new ExecResult();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream errb = new ByteArrayOutputStream();
        try {
            s = newSession(c);
            ch = (ChannelExec) s.openChannel("exec");
            ch.setCommand(cmd);
            ch.setInputStream(null);
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
                }
                if (ch.isClosed()) {
                    while (in.available() > 0) { int i = in.read(buf); if (i < 0) break; if (out.size() < 200000) out.write(buf, 0, i); }
                    break;
                }
                if (System.currentTimeMillis() > deadline) { r.err = "远端命令超时（" + timeoutSec + "s）"; break; }
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
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

    private static String rootMsg(Throwable e) {
        Throwable c = e;
        for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private RemoteCmdExec() {}
}
