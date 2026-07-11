package com.pharma.service.access.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存限流器（无 Redis 依赖）：限次（总配额）+ 限流（QPS 固定窗口）。
 * <p>计数存内存，进程重启归零（已与用户确认可接受）。
 */
@Component
public class RateLimiter {

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
        // 限次（总配额）
        if (limitCount > 0) {
            AtomicLong c = countMap.computeIfAbsent(appKey, k -> new AtomicLong(0));
            if (c.incrementAndGet() > limitCount) {
                c.decrementAndGet(); // 超限回退，不计入
                return "超出限次配额(上限" + limitCount + "次)";
            }
        }
        return null;
    }

    /** 当前累计调用次数（供管理列表展示） */
    public long getCount(String appKey) {
        AtomicLong c = countMap.get(appKey);
        return c == null ? 0 : c.get();
    }
}
