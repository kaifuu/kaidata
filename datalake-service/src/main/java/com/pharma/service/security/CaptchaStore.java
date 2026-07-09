package com.pharma.service.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验证码内存存储（数据安全域）
 * <p>
 * 单体应用内存级存储，无需 Redis。每条验证码带 2 分钟 TTL，校验时一次性消费
 * （无论对错都删除，防止对同一 captchaId 反复试码）。定时清扫未参与校验的过期孤儿条目。
 */
@Component
public class CaptchaStore {

    private static final long TTL_MS = 120_000L;   // 2 分钟
    private static final int CAP = 10_000;          // 容量上限，防 flood

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /** 暂存验证码，返回 captchaId */
    public String create(String code) {
        if (store.size() > CAP) sweep();
        String id = UUID.randomUUID().toString().replace("-", "");
        store.put(id, new Entry(code, System.currentTimeMillis() + TTL_MS));
        return id;
    }

    /**
     * 校验验证码（原子一次性消费）。
     * <p>
     * 用 computeIfPresent 在一步内完成「判过期 + 比对 + 删除」，避免 TOCTOU；
     * 校验失败也消费，杜绝对同一 captchaId 反复试码。
     *
     * @return true=匹配；false=不存在/已用过/过期/不匹配/参数为空
     */
    public boolean verify(String captchaId, String code) {
        if (captchaId == null || code == null) return false;
        boolean[] ok = {false};
        store.computeIfPresent(captchaId, (k, v) -> {
            long now = System.currentTimeMillis();
            ok[0] = now < v.expireAt && code.equalsIgnoreCase(v.code);
            return null;   // 无论对错都删除
        });
        return ok[0];
    }

    /** 定时清扫过期条目（GET 后未登录的孤儿条目只靠此清理 + CAP 兜底） */
    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now >= e.getValue().expireAt);
    }

    private record Entry(String code, long expireAt) {}
}
