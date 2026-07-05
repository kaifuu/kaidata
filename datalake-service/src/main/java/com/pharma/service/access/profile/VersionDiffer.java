package com.pharma.service.access.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表结构版本比对：本次字段集合 vs 上次快照 → 新增/删除/类型变化。
 * 输入为 列名→列类型 的 Map（来自 describeTable）。
 */
public final class VersionDiffer {

    public static class Diff {
        public final List<String> added = new ArrayList<>();
        public final List<String> removed = new ArrayList<>();
        public final List<String> typeChanged = new ArrayList<>(); // "name: prev -> cur"
        public boolean hasChange() { return !added.isEmpty() || !removed.isEmpty() || !typeChanged.isEmpty(); }
    }

    private VersionDiffer() {}

    public static Diff diff(Map<String, String> prev, Map<String, String> cur) {
        Diff d = new Diff();
        if (prev == null) prev = Map.of();
        if (cur == null) cur = Map.of();
        for (String n : cur.keySet()) if (!prev.containsKey(n)) d.added.add(n);
        for (String n : prev.keySet()) if (!cur.containsKey(n)) d.removed.add(n);
        for (String n : cur.keySet()) {
            if (prev.containsKey(n) && !eq(prev.get(n), cur.get(n))) {
                d.typeChanged.add(n + ": " + prev.get(n) + " -> " + cur.get(n));
            }
        }
        return d;
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equalsIgnoreCase(b == null ? "" : b);
    }
}
