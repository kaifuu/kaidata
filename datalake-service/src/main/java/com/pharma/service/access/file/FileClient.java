package com.pharma.service.access.file;

import java.util.List;
import java.util.Map;

/**
 * 文件客户端 SPI：屏蔽 FTP/SFTP/HDFS 的操作差异。
 * <p>每个方法自包含连接（connect→操作→disconnect），store Map 含 host/port/username/password/base_path/props。
 * download/upload 用 byte[]（演示小文件；大文件留流式优化）。
 */
public interface FileClient {

    String type();

    /** 探测连通，返回 {ok, msg?}。 */
    Map<String, Object> testConnection(Map<String, Object> store);

    /** 列目录：[{name, isDir, size, modTime, path}]。 */
    List<Map<String, Object>> list(Map<String, Object> store, String path);

    boolean mkdir(Map<String, Object> store, String path);

    boolean delete(Map<String, Object> store, String path);

    boolean copy(Map<String, Object> store, String src, String dst);

    byte[] download(Map<String, Object> store, String path);

    void upload(Map<String, Object> store, String path, byte[] data);
}
