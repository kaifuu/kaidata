package com.pharma.service.access.adapter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器注册中心 + 外部数据源连接池缓存。
 * <p>启动时扫描所有 {@link DataSourceAdapter} Bean 按 type 索引；
 * 按数据源 id 缓存 HikariDataSource（LRU 上限 20，access-order 驱逐最久未用并 close）。
 */
@Component
public class DataSourceAdapterRegistry {

    private final Map<String, DataSourceAdapter> adapters = new java.util.HashMap<>();
    private final int maxPoolSize;

    private final Map<Long, HikariDataSource> pools = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, HikariDataSource> eldest) {
                    if (size() > 20) { try { eldest.getValue().close(); } catch (Exception ignored) {} return true; }
                    return false;
                }
            });

    public DataSourceAdapterRegistry(List<DataSourceAdapter> all,
                                     @Value("${pharma.access.pool.max-size:5}") int maxPoolSize) {
        for (DataSourceAdapter a : all) adapters.put(a.type(), a);
        this.maxPoolSize = maxPoolSize;
    }

    public DataSourceAdapter adapter(String type) { return type == null ? null : adapters.get(type); }
    public Collection<DataSourceAdapter> all() { return adapters.values(); }

    /** 取/建指定数据源的连接池（仅 JDBC 类型；ES 等走专用接口）。 */
    public DataSource getPool(DataSourceDescriptor ds) {
        DataSourceAdapter a = adapters.get(ds.type);
        if (a == null) throw new IllegalStateException("不支持的数据源类型：" + ds.type);
        if (!a.driverAvailable()) throw new IllegalStateException(a.type() + " 驱动未就绪");
        if (a instanceof ElasticsearchAdapter) throw new IllegalStateException("Elasticsearch 不走 JDBC 连接池");
        synchronized (pools) {
            HikariDataSource p = pools.get(ds.id);
            if (p != null && !p.isClosed()) return p;
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(a.buildUrl(ds));
            cfg.setDriverClassName(a.driverClassName());
            cfg.setUsername(ds.username == null ? "" : ds.username);
            cfg.setPassword(ds.password == null ? "" : ds.password);
            cfg.setMaximumPoolSize(maxPoolSize);
            cfg.setPoolName("ds-" + ds.id);
            cfg.setConnectionTimeout(10000);
            p = new HikariDataSource(cfg);
            pools.put(ds.id, p);
            return p;
        }
    }

    /** 数据源配置变更/删除时淘汰旧池。 */
    public void evict(long dsId) {
        synchronized (pools) {
            HikariDataSource p = pools.remove(dsId);
            if (p != null) try { p.close(); } catch (Exception ignored) {}
        }
    }

    @PreDestroy
    public void closeAll() {
        synchronized (pools) {
            pools.values().forEach(p -> { try { p.close(); } catch (Exception ignored) {} });
            pools.clear();
        }
    }
}
