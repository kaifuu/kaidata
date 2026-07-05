#!/usr/bin/env bash
# Flink 缺席下的告警桥接：周期性把 ods_env_monitor 中越限的新行写入 ods_alarm（去重）。
export MSYS_NO_PATHCONV=1
SQL="INSERT INTO ods_alarm(device_id, metric, value, min_val, max_val, severity, ts)
SELECT e.device_id, e.metric, e.value, e.min_val, e.max_val,
       CASE WHEN e.value > e.max_val THEN 'CRITICAL' ELSE 'WARN' END, e.ts
FROM ods_env_monitor e
WHERE (e.value > e.max_val OR e.value < e.min_val)
  AND NOT EXISTS (SELECT 1 FROM ods_alarm a WHERE a.device_id=e.device_id AND a.metric=e.metric AND a.ts=e.ts);"
while true; do
  docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root ods -e "$SQL" >/dev/null 2>&1
  sleep 20
done
