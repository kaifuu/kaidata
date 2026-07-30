package com.pharma.service.access.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流器（无 Redis 依赖）：限次（总配额）+ 限流（QPS 固定窗口）。
 * <p>QPS 固定窗口内存（短时窗，重启可接受）；限次计数内存累加，但首访 lazy 从 data_open_grant.used_count
 * 恢复初值、每 10 次回写，重启不丢配额（单实例持久化）。多实例共享需 Redis。
 */
@Component
public class RateLimiter {

    @Autowired private JdbcTemplate jdbc;
    /** appKey -> 累计调用次数（限次校验） */
    private final ConcurrentHashMap<String, AtomicLong> countMap = new ConcurrentHashMap<>();
    /** appKey -> [秒戳, 当前秒计数]（限流 QPS 固定窗口） */
    private final ConcurrentHashMap<String, long[]> qpsWindow = new ConcurrentHashMap<>();

    /**
     * 校验是否放行。
     *
     * @param appKey     应用 Key
     * @param limitCount 限次配额（总调用次数上限，0=不限）
     * @param limitQps   限流（每秒请求数上限，0=不限）
     * @return null=放行；非空=拒绝原因
     */
    public String check(String appKey, long limitCount, int limitQps) {
        // 限流（QPS 固定窗口）
        if (limitQps > 0) {
            long now = System.currentTimeMillis() / 1000;
            long[] win = qpsWindow.compute(appKey, (k, v) -> {
                if (v == null || v[0] != now) return new long[]{now, 1};
                v[1] += 1;
                return v;
            });
            if (win[1] > limitQps) return "超出限流(QPS=" + limitQps + ")";
        }
        // 限次（总配额）：内存累加，首访 lazy 从 data_open_grant.used_count 恢复初值（重启不丢配额），每 10 次回写
        if (limitCount > 0) {
            AtomicLong c = countMap.get(appKey);
            if (c == null) { c = new AtomicLong(loadUsed(appKey)); countMap.put(appKey, c); }
            if (c.incrementAndGet() > limitCount) {
                c.decrementAndGet(); // 超限回退，不计入
                saveUsed(appKey, c.get());
                return "超出限次配额(上限" + limitCount + "次)";
            }
            if (c.get() % 10 == 0) saveUsed(appKey, c.get());
        }
        return null;
    }

    /** 从 DB 恢复已用配额（重启后 lazy 加载；多实例下仅作单实例初值）。 */
    private long loadUsed(String appKey) {
        try { Long v = jdbc.queryForObject("SELECT used_count FROM meta.data_open_grant WHERE app_key=?", Long.class, appKey); return v == null ? 0 : v; }
        catch (Exception e) { return 0; }
    }
    /** 回写已用配额到 DB（限次持久化）。 */
    private void saveUsed(String appKey, long n) {
        try { jdbc.update("UPDATE meta.data_open_grant SET used_count=? WHERE app_key=?", n, appKey); } catch (Exception ignored) {}
    }

    /** 当前累计调用次数（供管理列表展示） */
    public long getCount(String appKey) {
        AtomicLong c = countMap.get(appKey);
        return c == null ? 0 : c.get();
    }
}
