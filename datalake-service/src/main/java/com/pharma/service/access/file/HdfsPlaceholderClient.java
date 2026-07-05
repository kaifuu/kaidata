package com.pharma.service.access.file;

import java.util.List;
import java.util.Map;

/**
 * HDFS 占位客户端：依赖 hadoop-hdfs-client（profile with-hdfs，默认不启用）。
 * <p>启用 profile 且部署 HDFS 后，替换为真实 HdfsFileClient（基于 FileSystem）。
 */
public class HdfsPlaceholderClient implements FileClient {

    @Override public String type() { return "hdfs"; }

    private static Map<String, Object> hint() {
        return Map.of("ok", false, "msg", "HDFS 客户端未启用：需用 Maven profile with-hdfs 构建，且部署可访问的 HDFS 集群。");
    }

    @Override public Map<String, Object> testConnection(Map<String, Object> store) { return hint(); }
    @Override public List<Map<String, Object>> list(Map<String, Object> store, String path) { throw new UnsupportedOperationException("HDFS 未启用"); }
    @Override public boolean mkdir(Map<String, Object> store, String path) { throw new UnsupportedOperationException("HDFS 未启用"); }
    @Override public boolean delete(Map<String, Object> store, String path) { throw new UnsupportedOperationException("HDFS 未启用"); }
    @Override public boolean copy(Map<String, Object> store, String src, String dst) { throw new UnsupportedOperationException("HDFS 未启用"); }
    @Override public byte[] download(Map<String, Object> store, String path) { throw new UnsupportedOperationException("HDFS 未启用"); }
    @Override public void upload(Map<String, Object> store, String path, byte[] data) { throw new UnsupportedOperationException("HDFS 未启用"); }
}
