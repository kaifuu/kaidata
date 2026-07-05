package com.pharma.service.access.adapter;

import com.pharma.service.access.util.CryptoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 从 meta.ing_datasource 加载单条数据源（解密密码 → 明文填入 descriptor）。
 * 供离线/实时/文件等模块复用，避免在各 controller 重复实现。
 */
@Component
public class DataSourceLoader {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CryptoUtil crypto;

    public DataSourceDescriptor load(long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, name, type, host, port, db_name, username, password, props " +
                        "FROM meta.ing_datasource WHERE id=?", id);
        DataSourceDescriptor ds = new DataSourceDescriptor();
        ds.id = ((Number) row.get("id")).longValue();
        ds.name = str(row.get("name"));
        ds.type = str(row.get("type"));
        ds.host = str(row.get("host"));
        Object p = row.get("port");
        ds.port = p == null ? 0 : ((Number) p).intValue();
        ds.dbName = str(row.get("db_name"));
        ds.username = str(row.get("username"));
        ds.password = crypto.decrypt(str(row.get("password")));
        ds.props = str(row.get("props"));
        return ds;
    }

    public static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
