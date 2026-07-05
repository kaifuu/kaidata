package com.pharma.service.access.file;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** FTP 文件客户端（commons-net），被动模式。每次操作自包含连接。 */
public class FtpFileClient implements FileClient {

    @Override public String type() { return "ftp"; }

    private FTPClient connect(Map<String, Object> store) throws Exception {
        FTPClient ftp = new FTPClient();
        ftp.setDefaultTimeout(10000);
        ftp.connect(StoreConfig.s(store, "host"), StoreConfig.port(store, 21));
        if (!ftp.login(StoreConfig.s(store, "username"), StoreConfig.s(store, "password")))
            throw new RuntimeException("FTP 登录失败");
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return ftp;
    }

    private void disconnect(FTPClient ftp) {
        if (ftp == null) return;
        try { ftp.logout(); } catch (Exception ignored) {}
        try { ftp.disconnect(); } catch (Exception ignored) {}
    }

    @FunctionalInterface private interface FtpAction<T> { T run(FTPClient ftp) throws Exception; }
    private <T> T with(Map<String, Object> store, FtpAction<T> action) {
        FTPClient ftp = null;
        try { ftp = connect(store); return action.run(ftp); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(rootMsg(e), e); }
        finally { disconnect(ftp); }
    }

    @Override public Map<String, Object> testConnection(Map<String, Object> store) {
        FTPClient ftp = null;
        try { ftp = connect(store); return Map.of("ok", true, "msg", "FTP 连通成功"); }
        catch (Exception e) { return Map.of("ok", false, "msg", rootMsg(e)); }
        finally { disconnect(ftp); }
    }

    @Override public List<Map<String, Object>> list(Map<String, Object> store, String path) {
        return with(store, ftp -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (FTPFile f : ftp.listFiles(StoreConfig.resolve(store, path))) {
                if (".".equals(f.getName()) || "..".equals(f.getName())) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", f.getName());
                r.put("isDir", f.isDirectory());
                r.put("size", f.getSize());
                r.put("modTime", f.getTimestamp() == null ? "" : f.getTimestamp().getTime().toString());
                r.put("path", join(path, f.getName()));
                out.add(r);
            }
            return out;
        });
    }

    @Override public boolean mkdir(Map<String, Object> store, String path) {
        return with(store, ftp -> ftp.makeDirectory(StoreConfig.resolve(store, path)));
    }

    @Override public boolean delete(Map<String, Object> store, String path) {
        return with(store, ftp -> {
            String full = StoreConfig.resolve(store, path);
            return ftp.deleteFile(full) || ftp.removeDirectory(full);
        });
    }

    @Override public boolean copy(Map<String, Object> store, String src, String dst) {
        byte[] data = download(store, src);
        upload(store, dst, data);
        return true;
    }

    @Override public byte[] download(Map<String, Object> store, String path) {
        return with(store, ftp -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ftp.retrieveFile(StoreConfig.resolve(store, path), out)) throw new RuntimeException("下载失败");
            return out.toByteArray();
        });
    }

    @Override public void upload(Map<String, Object> store, String path, byte[] data) {
        with(store, ftp -> {
            try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
                if (!ftp.storeFile(StoreConfig.resolve(store, path), in)) throw new RuntimeException("上传失败");
            }
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
