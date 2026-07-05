package com.pharma.service.access.file;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/** SFTP 文件客户端（jsch / mwiede fork，包名兼容 com.jcraft.jsch）。 */
public class SftpFileClient implements FileClient {

    @Override public String type() { return "sftp"; }

    private Session newSession(Map<String, Object> store) throws Exception {
        JSch jsch = new JSch();
        Session s = jsch.getSession(StoreConfig.s(store, "username"), StoreConfig.s(store, "host"),
                StoreConfig.port(store, 22));
        s.setPassword(StoreConfig.s(store, "password"));
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(10000);
        return s;
    }

    @FunctionalInterface private interface SftpAction<T> { T run(ChannelSftp ch) throws Exception; }
    private <T> T with(Map<String, Object> store, SftpAction<T> action) {
        Session session = null;
        ChannelSftp ch = null;
        try {
            session = newSession(store);
            ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(10000);
            return action.run(ch);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(rootMsg(e), e); }
        finally { if (ch != null) ch.disconnect(); if (session != null) session.disconnect(); }
    }

    @Override public Map<String, Object> testConnection(Map<String, Object> store) {
        Session s = null;
        try { s = newSession(store); return Map.of("ok", true, "msg", "SFTP 连通成功"); }
        catch (Exception e) { return Map.of("ok", false, "msg", rootMsg(e)); }
        finally { if (s != null) s.disconnect(); }
    }

    @Override public List<Map<String, Object>> list(Map<String, Object> store, String path) {
        return with(store, ch -> {
            List<Map<String, Object>> out = new ArrayList<>();
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> es = ch.ls(StoreConfig.resolve(store, path));
            for (ChannelSftp.LsEntry e : es) {
                if (".".equals(e.getFilename()) || "..".equals(e.getFilename())) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", e.getFilename());
                r.put("isDir", e.getAttrs().isDir());
                r.put("size", e.getAttrs().getSize());
                r.put("modTime", String.valueOf(e.getAttrs().getMTime()));
                r.put("path", join(path, e.getFilename()));
                out.add(r);
            }
            return out;
        });
    }

    @Override public boolean mkdir(Map<String, Object> store, String path) {
        return with(store, ch -> { ch.mkdir(StoreConfig.resolve(store, path)); return true; });
    }

    @Override public boolean delete(Map<String, Object> store, String path) {
        return with(store, ch -> {
            try { ch.rm(StoreConfig.resolve(store, path)); return true; }
            catch (SftpException e) { ch.rmdir(StoreConfig.resolve(store, path)); return true; }
        });
    }

    @Override public boolean copy(Map<String, Object> store, String src, String dst) {
        byte[] data = download(store, src);
        upload(store, dst, data);
        return true;
    }

    @Override public byte[] download(Map<String, Object> store, String path) {
        return with(store, ch -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (var in = ch.get(StoreConfig.resolve(store, path))) { in.transferTo(out); }
            return out.toByteArray();
        });
    }

    @Override public void upload(Map<String, Object> store, String path, byte[] data) {
        with(store, ch -> {
            try (ByteArrayInputStream in = new ByteArrayInputStream(data)) { ch.put(in, StoreConfig.resolve(store, path)); }
            return null;
        });
    }

    private static String join(String base, String name) {
        if (base == null || base.isEmpty()) return name;
        return base + "/" + name;
    }
    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
