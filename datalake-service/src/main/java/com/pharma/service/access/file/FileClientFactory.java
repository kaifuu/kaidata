package com.pharma.service.access.file;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** 按 type 创建/复用 FileClient（FTP/SFTP 真实，HDFS 占位）。 */
@Component
public class FileClientFactory {

    private final Map<String, FileClient> clients = new HashMap<>();

    public FileClientFactory() {
        register(new FtpFileClient());
        register(new SftpFileClient());
        register(new HdfsPlaceholderClient());
    }

    public void register(FileClient c) { clients.put(c.type(), c); }

    public FileClient get(String type) {
        FileClient c = clients.get(type);
        if (c == null) throw new IllegalArgumentException("不支持的文件存储类型：" + type);
        return c;
    }
}
