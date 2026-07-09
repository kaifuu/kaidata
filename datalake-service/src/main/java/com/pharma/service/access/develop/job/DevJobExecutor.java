package com.pharma.service.access.develop.job;

import java.util.Map;

/**
 * 离线作业执行器：按 job_type 分发。
 * <p>实现类注册 {@link #jobType()}，由 DevOfflineExecutor 按 task.job_type 路由。
 * <p>task Map 含字段：name / job_type / datasource_id / sql_content / dag_json / config_json。
 * <p>job_type 取值：jdbc_sql（兼容旧 JDBC 任务）/ flink_sql / flink_jar / flink_dag / kettle_hop。
 */
public interface DevJobExecutor {

    String jobType();

    /**
     * 执行作业。
     *
     * @param taskId 任务 id
     * @param task   任务行（含 sql_content/dag_json/config_json 等）
     * @param log    日志收集器（向其 append 执行过程，最终落 dev_offline_run.log_text）
     * @return 至少含 engineJobId（引擎返回的作业 id，无则空串）、rowsRead、cols
     * @throws Exception 执行失败（由 DevOfflineExecutor 捕获并落 FAIL）
     */
    Map<String, Object> execute(long taskId, Map<String, Object> task, StringBuilder log) throws Exception;
}
