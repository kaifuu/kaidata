package com.pharma.service.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * 元数据库（meta）初始化与种子数据。
 * <p>
 * 启动时幂等执行：建库建表（含租户/组织/用户/角色/菜单/授权/审计），
 * 并保证种子存在——三员角色、两个租户、组织树、三员用户、6 个系统管理菜单及角色-菜单授权。
 * 元数据放 StarRocks 的 meta 库（复用单一数据源；生产应迁 MySQL）。
 * <p>
 * 三员分立：SYS_ADMIN(系统管理员) / SEC_ADMIN(安全保密管理员) / AUDIT_ADMIN(安全审计员)，
 * 各自只能管理职责范围内的对象（见 SystemController 的 Authz 闸）。
 * 另保留 admin/admin123（三员合一）便于演示全貌。
 */
@Component
public class MetaSeedRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    public MetaSeedRunner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        ddl();
        seed();
        System.out.println("[Meta] 就绪：租户 " + cnt("SELECT COUNT(*) FROM meta.sys_tenant")
                + " / 组织 " + cnt("SELECT COUNT(*) FROM meta.sys_org")
                + " / 用户 " + cnt("SELECT COUNT(*) FROM meta.sys_user")
                + " / 角色 " + cnt("SELECT COUNT(*) FROM meta.sys_role")
                + " / 菜单 " + cnt("SELECT COUNT(*) FROM meta.sys_menu"));
    }

    private void ddl() {
        exec("CREATE DATABASE IF NOT EXISTS meta");

        // 版本标记表（PRIMARY KEY 支持 upsert，记录 schema 迁移版本）
        exec("CREATE TABLE IF NOT EXISTS meta.sys_kv (k VARCHAR(64), v VARCHAR(64)) PRIMARY KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        boolean migrated = "2".equals(kv("schema_ver"));

        if (!migrated) {
            // v1 的 CRUD 表为 UNIQUE KEY，不支持 UPDATE/DELETE；v2 重建为 PRIMARY KEY。
            // 审计日志(sys_audit_log)为追加型 DUPLICATE KEY，保留不动。
            for (String t : new String[]{"sys_user_role", "sys_role_menu", "sys_user", "sys_org", "sys_tenant", "sys_role", "sys_menu"}) {
                exec("DROP TABLE IF EXISTS meta." + t);
            }
        }

        // CRUD 表一律 PRIMARY KEY（主键列须为列前缀）→ 支持 UPDATE/DELETE，满足管理界面编辑/删除
        exec("CREATE TABLE IF NOT EXISTS meta.sys_tenant (id BIGINT, code VARCHAR(64), name VARCHAR(128), status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sys_org (id BIGINT, tenant_id BIGINT, parent_id BIGINT, code VARCHAR(64), name VARCHAR(128), sort INT, create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sys_user (id BIGINT, username VARCHAR(64), password VARCHAR(128), name VARCHAR(64), status VARCHAR(16), tenant_id BIGINT, org_id BIGINT, create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sys_role (id BIGINT, code VARCHAR(64), name VARCHAR(64)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sys_menu (id BIGINT, parent_id BIGINT, name VARCHAR(64), path VARCHAR(128), icon VARCHAR(64), perm VARCHAR(64), type VARCHAR(16), sort INT) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 菜单启停字段（ALTER 幂等，已存在则忽略；旧数据 status 为 NULL → 统一置 ENABLED）
        exec("ALTER TABLE meta.sys_menu ADD COLUMN status VARCHAR(16)");
        exec("UPDATE meta.sys_menu SET status='ENABLED' WHERE status IS NULL");
        exec("CREATE TABLE IF NOT EXISTS meta.sys_user_role (user_id BIGINT, role_id BIGINT) PRIMARY KEY(user_id, role_id) DISTRIBUTED BY HASH(user_id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sys_role_menu (role_id BIGINT, menu_id BIGINT) PRIMARY KEY(role_id, menu_id) DISTRIBUTED BY HASH(role_id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");

        // 审计日志（追加型 DUPLICATE KEY；补 action 列：操作描述）
        exec("CREATE TABLE IF NOT EXISTS meta.sys_audit_log (id BIGINT, username VARCHAR(64), uri VARCHAR(255), method VARCHAR(16), params VARCHAR(255), result VARCHAR(32), ip VARCHAR(64), ts DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("ALTER TABLE meta.sys_audit_log ADD COLUMN action VARCHAR(255)");

        if (!migrated) kvSet("schema_ver", "2");

        // ============ 数据接入子系统元数据表（schema_ver=3，增量；不动 v2 迁移） ============
        boolean ing = "3".equals(kv("schema_ver"));
        // 数据源（13 类型，密码 AES-GCM 密文）
        exec("CREATE TABLE IF NOT EXISTS meta.ing_datasource (" +
                "id BIGINT, name VARCHAR(128), type VARCHAR(32), host VARCHAR(128), port INT, " +
                "db_name VARCHAR(128), username VARCHAR(128), password VARCHAR(512), props VARCHAR(2048), " +
                "status VARCHAR(16), tenant_id BIGINT, create_by VARCHAR(64), create_time DATETIME, update_time DATETIME" +
                ") PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 文件存储源（FTP/SFTP/HDFS）
        exec("CREATE TABLE IF NOT EXISTS meta.ing_filestore (" +
                "id BIGINT, name VARCHAR(128), type VARCHAR(16), host VARCHAR(128), port INT, " +
                "username VARCHAR(128), password VARCHAR(512), base_path VARCHAR(512), props VARCHAR(2048), " +
                "status VARCHAR(16), create_by VARCHAR(64), create_time DATETIME, update_time DATETIME" +
                ") PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 已登记/已接入文件
        exec("CREATE TABLE IF NOT EXISTS meta.ing_file (" +
                "id BIGINT, store_id BIGINT, path VARCHAR(1024), name VARCHAR(255), size BIGINT, is_dir BOOLEAN, " +
                "file_type VARCHAR(16), target_table VARCHAR(128), rows_written BIGINT, ingested BOOLEAN, create_time DATETIME" +
                ") PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 离线接入任务
        exec("CREATE TABLE IF NOT EXISTS meta.ing_offline_job (" +
                "id BIGINT, name VARCHAR(128), source_ds_id BIGINT, source_table VARCHAR(255), " +
                "target_db VARCHAR(64), target_table VARCHAR(128), strategy VARCHAR(16), inc_column VARCHAR(64), " +
                "biz_key VARCHAR(128), last_sync_value VARCHAR(64), column_map VARCHAR(4096), status VARCHAR(16), " +
                "create_by VARCHAR(64), create_time DATETIME, update_time DATETIME" +
                ") PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 离线执行历史（追加）
        exec("CREATE TABLE IF NOT EXISTS meta.ing_offline_run (" +
                "id BIGINT, job_id BIGINT, start_time DATETIME, end_time DATETIME, status VARCHAR(16), " +
                "rows_read BIGINT, rows_written BIGINT, error_msg VARCHAR(2048), triggered_by VARCHAR(64)" +
                ") DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 实时管道任务
        exec("CREATE TABLE IF NOT EXISTS meta.ing_stream_job (" +
                "id BIGINT, name VARCHAR(128), type VARCHAR(32), source_ds_id BIGINT, source_query VARCHAR(2048), " +
                "kafka_topic VARCHAR(128), target_db VARCHAR(64), target_table VARCHAR(128), columns_json VARCHAR(2048), " +
                "schedule_cron VARCHAR(64), status VARCHAR(16), props VARCHAR(2048), " +
                "create_by VARCHAR(64), create_time DATETIME, update_time DATETIME" +
                ") PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 实时执行历史（追加）
        exec("CREATE TABLE IF NOT EXISTS meta.ing_stream_run (" +
                "id BIGINT, job_id BIGINT, start_time DATETIME, end_time DATETIME, status VARCHAR(16), " +
                "rows_in BIGINT, rows_out BIGINT, error_msg VARCHAR(2048)" +
                ") DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        if (!ing) kvSet("schema_ver", "3");

        // ============ 数据探查子系统元数据表（schema_ver=4，增量） ============
        boolean prof = "4".equals(kv("schema_ver"));
        // 探查任务
        exec("CREATE TABLE IF NOT EXISTS meta.ing_profile_job (" +
                "id BIGINT, name VARCHAR(128), source_ds_id BIGINT, target_db VARCHAR(64), " +
                "first_create_table BOOLEAN, alert_enabled BOOLEAN, extra_columns VARCHAR(4096), " +
                "cron VARCHAR(64), status VARCHAR(16), tenant_id BIGINT, " +
                "create_by VARCHAR(64), create_time DATETIME, update_time DATETIME" +
                ") PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 任务-表配置（一个任务多张表，每张表一行：勾选的字段探查项）
        exec("CREATE TABLE IF NOT EXISTS meta.ing_profile_table (" +
                "id BIGINT, job_id BIGINT, table_name VARCHAR(255), is_view BOOLEAN, columns_config VARCHAR(8192)" +
                ") PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 探查快照（追加，version_n 按表+任务递增）
        exec("CREATE TABLE IF NOT EXISTS meta.ing_profile_snapshot (" +
                "id BIGINT, job_id BIGINT, table_name VARCHAR(255), version_n INT, " +
                "columns_json VARCHAR(65535), stats_json VARCHAR(65535), run_id BIGINT, created_time DATETIME" +
                ") DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 版本差异（列新增/删除/类型变化）
        exec("CREATE TABLE IF NOT EXISTS meta.ing_profile_diff (" +
                "id BIGINT, snapshot_id BIGINT, job_id BIGINT, table_name VARCHAR(255), " +
                "added VARCHAR(8192), removed VARCHAR(8192), type_changed VARCHAR(8192), created_time DATETIME" +
                ") DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // 执行日志（含完整 log_text 可复制）
        exec("CREATE TABLE IF NOT EXISTS meta.ing_profile_run (" +
                "id BIGINT, job_id BIGINT, start_time DATETIME, end_time DATETIME, status VARCHAR(16), " +
                "tables_changed INT, tables_total INT, error_msg VARCHAR(2048), log_text VARCHAR(65535), triggered_by VARCHAR(64)" +
                ") DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        if (!prof) kvSet("schema_ver", "4");

        // ============ 数据治理子系统元数据表（schema_ver=5，增量） ============
        boolean gov = "5".equals(kv("schema_ver"));
        // ①数据标准：数据元 + 代码集 + 代码项
        exec("CREATE TABLE IF NOT EXISTS meta.gov_data_element (id BIGINT, code VARCHAR(64), name VARCHAR(128), data_type VARCHAR(32), length INT, precision_ INT, scale_ INT, definition VARCHAR(1024), value_domain VARCHAR(2048), status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_code_set (id BIGINT, code VARCHAR(64), name VARCHAR(128), description VARCHAR(512), status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_code_item (id BIGINT, set_id BIGINT, code VARCHAR(64), name VARCHAR(128), sort INT) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ②数据模型：模型 + 模型表 + 模型字段
        exec("CREATE TABLE IF NOT EXISTS meta.gov_model (id BIGINT, name VARCHAR(128), domain VARCHAR(64), model_type VARCHAR(32), description VARCHAR(512), status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_model_table (id BIGINT, model_id BIGINT, name VARCHAR(128), layer VARCHAR(32), description VARCHAR(512)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_model_field (id BIGINT, table_id BIGINT, name VARCHAR(128), data_type VARCHAR(64), element_id BIGINT, is_pk BOOLEAN, nullable BOOLEAN, comment VARCHAR(512)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ③数据仓库：层级 + 层级-数据源绑定 + 主题域
        exec("CREATE TABLE IF NOT EXISTS meta.gov_layer (id BIGINT, code VARCHAR(32), name VARCHAR(64), sort INT, status VARCHAR(16)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_layer_datasource (id BIGINT, layer_code VARCHAR(32), datasource_id BIGINT) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_subject (id BIGINT, code VARCHAR(64), name VARCHAR(128), parent_id BIGINT, sort INT) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ④数据质量：规则 + 任务 + 结果
        exec("CREATE TABLE IF NOT EXISTS meta.gov_quality_rule (id BIGINT, name VARCHAR(128), dimension VARCHAR(32), ds_id BIGINT, table_name VARCHAR(255), column_name VARCHAR(128), expression VARCHAR(512), threshold DOUBLE, status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_quality_task (id BIGINT, name VARCHAR(128), rule_ids VARCHAR(2048), cron VARCHAR(64), status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_quality_result (id BIGINT, task_id BIGINT, rule_id BIGINT, status VARCHAR(16), value DOUBLE, threshold DOUBLE, violate_count BIGINT, total_count BIGINT, error_msg VARCHAR(1024), run_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ⑤元数据：表元数据（字段存 columns_json）
        exec("CREATE TABLE IF NOT EXISTS meta.gov_meta_table (id BIGINT, ds_id BIGINT, schema_name VARCHAR(128), table_name VARCHAR(255), comment VARCHAR(512), columns_json VARCHAR(65535), row_count BIGINT, synced_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ⑥数据标签：标签 + 打标关系
        exec("CREATE TABLE IF NOT EXISTS meta.gov_tag (id BIGINT, name VARCHAR(64), category VARCHAR(32), color VARCHAR(16), description VARCHAR(256)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_tag_relation (id BIGINT, tag_id BIGINT, target_type VARCHAR(16), target_db VARCHAR(64), target_table VARCHAR(128), target_column VARCHAR(128)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ⑦主数据：主数据定义 + 记录
        exec("CREATE TABLE IF NOT EXISTS meta.gov_master (id BIGINT, code VARCHAR(64), name VARCHAR(128), description VARCHAR(512), fields_json VARCHAR(8192), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.gov_master_record (id BIGINT, master_id BIGINT, data_json VARCHAR(16384), create_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");

        if (!gov) {
            // 层级种子（数据仓库分层）
            seedLayer("ods", "原始数据层", 1);
            seedLayer("dwd", "明细数据层", 2);
            seedLayer("dws", "汇总数据层", 3);
            seedLayer("ads", "应用数据层", 4);
            seedLayer("dim", "维度数据层", 5);
            kvSet("schema_ver", "5");
        }

        // ============ 数据开发子系统元数据表（schema_ver=6，增量） ============
        boolean dev = "6".equals(kv("schema_ver"));
        // ①函数管理
        exec("CREATE TABLE IF NOT EXISTS meta.dev_function (id BIGINT, name VARCHAR(128), func_type VARCHAR(16), language VARCHAR(16), body VARCHAR(65535), return_type VARCHAR(64), description VARCHAR(512), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ②数据开发：脚本 + 执行历史
        exec("CREATE TABLE IF NOT EXISTS meta.dev_script (id BIGINT, name VARCHAR(128), script_type VARCHAR(16), datasource_id BIGINT, content VARCHAR(65535), description VARCHAR(512), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.dev_script_run (id BIGINT, script_id BIGINT, status VARCHAR(16), rows_read BIGINT, cols INT, error_msg VARCHAR(2048), run_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ③数据接出：导出定义 + 历史
        exec("CREATE TABLE IF NOT EXISTS meta.dev_export (id BIGINT, name VARCHAR(128), source_ds_id BIGINT, source_query VARCHAR(65535), target_type VARCHAR(16), target_config VARCHAR(2048), format VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.dev_export_run (id BIGINT, export_id BIGINT, status VARCHAR(16), rows_out BIGINT, error_msg VARCHAR(2048), run_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        // ④工作流：定义 + 节点 + 历史
        exec("CREATE TABLE IF NOT EXISTS meta.dev_workflow (id BIGINT, name VARCHAR(128), cron VARCHAR(64), status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.dev_workflow_node (id BIGINT, workflow_id BIGINT, node_type VARCHAR(16), node_id BIGINT, sort INT) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.dev_workflow_run (id BIGINT, workflow_id BIGINT, status VARCHAR(16), nodes_total INT, nodes_pass INT, log_text VARCHAR(65535), run_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        if (!dev) kvSet("schema_ver", "6");

        // ============ 数据资产子系统元数据表（schema_ver=7，增量） ============
        boolean ast = "7".equals(kv("schema_ver"));
        exec("CREATE TABLE IF NOT EXISTS meta.asset_catalog (id BIGINT, code VARCHAR(64), name VARCHAR(128), parent_id BIGINT, node_type VARCHAR(32), sort INT) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.asset (id BIGINT, catalog_id BIGINT, name VARCHAR(128), asset_type VARCHAR(32), source_type VARCHAR(32), source_id BIGINT, owner VARCHAR(64), security_level VARCHAR(16), description VARCHAR(1024), status VARCHAR(16), create_by VARCHAR(64), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.asset_audit (id BIGINT, asset_id BIGINT, action VARCHAR(16), comment VARCHAR(1024), auditor VARCHAR(64), audit_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        if (!ast) kvSet("schema_ver", "7");

        // ============ 数据安全子系统元数据表（schema_ver=8，增量） ============
        boolean sec = "8".equals(kv("schema_ver"));
        exec("CREATE TABLE IF NOT EXISTS meta.sec_standard (id BIGINT, code VARCHAR(64), name VARCHAR(128), level INT, description VARCHAR(512), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sec_mask_rule (id BIGINT, name VARCHAR(128), mask_type VARCHAR(32), pattern VARCHAR(256), replacement VARCHAR(128), description VARCHAR(512)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sec_mask_rel (id BIGINT, rule_id BIGINT, source_table VARCHAR(255), source_column VARCHAR(128)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sec_key (id BIGINT, name VARCHAR(128), algo VARCHAR(32), key_value VARCHAR(1024), status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sec_alert_def (id BIGINT, name VARCHAR(128), source VARCHAR(32), condition_cfg VARCHAR(1024), notify_channels VARCHAR(256), enabled BOOLEAN, create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sec_alert_event (id BIGINT, def_id BIGINT, level VARCHAR(16), message VARCHAR(1024), status VARCHAR(16), created_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sec_ip_list (id BIGINT, ip VARCHAR(128), list_type VARCHAR(16), scope VARCHAR(64), comment VARCHAR(256), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sec_sensitive (id BIGINT, source_table VARCHAR(255), source_column VARCHAR(128), sensitive_type VARCHAR(32), level VARCHAR(16), mask_rule_id BIGINT, description VARCHAR(512)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.sec_data_perm (id BIGINT, role_id BIGINT, target_db VARCHAR(64), target_table VARCHAR(128), permission VARCHAR(32), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        if (!sec) {
            // 安全分级种子
            seedStd("PUBLIC", "公开", 1, "可公开数据");
            seedStd("INTERNAL", "内部", 2, "内部使用");
            seedStd("SENSITIVE", "敏感", 3, "敏感数据，需脱敏");
            seedStd("CONFIDENTIAL", "机密", 4, "机密数据，严格管控");
            // 默认脱敏规则种子
            seedMask("手机号", "PHONE", "(\\d{3})\\d{4}(\\d{4})", "$1****$2");
            seedMask("身份证号", "IDCARD", "(\\d{4})\\d{10}(\\w{4})", "$1**********$2");
            seedMask("邮箱", "EMAIL", "(\\w{1})\\w*@(\\w+)", "$1***@$2");
            kvSet("schema_ver", "8");
        }

        // ============ 数据服务子系统元数据表（schema_ver=9，增量） ============
        boolean dsv = "9".equals(kv("schema_ver"));
        exec("CREATE TABLE IF NOT EXISTS meta.data_service (id BIGINT, code VARCHAR(64), name VARCHAR(128), sql_text VARCHAR(65535), datasource_id BIGINT, method VARCHAR(8), params VARCHAR(2048), path VARCHAR(128), auth BOOLEAN, status VARCHAR(16), create_time DATETIME) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        exec("CREATE TABLE IF NOT EXISTS meta.data_service_log (id BIGINT, service_id BIGINT, cost_ms INT, status VARCHAR(16), params VARCHAR(1024), ip VARCHAR(64), caller VARCHAR(64), call_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        if (!dsv) kvSet("schema_ver", "9");

        // ============ 数据集市（门户）购物车（schema_ver=10，增量） ============
        boolean mkt = "10".equals(kv("schema_ver"));
        exec("CREATE TABLE IF NOT EXISTS meta.portal_cart (id BIGINT, username VARCHAR(64), item_type VARCHAR(16), item_ref VARCHAR(255), item_name VARCHAR(255), add_time DATETIME) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
        if (!mkt) kvSet("schema_ver", "10");
    }

    private void seedStd(String code, String name, int level, String desc) {
        if (cnt("SELECT COUNT(*) FROM meta.sec_standard WHERE code='" + code + "'") > 0) return;
        jdbc.update("INSERT INTO meta.sec_standard(id, code, name, level, description, create_time) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis() + level, code, name, level, desc, new Timestamp(System.currentTimeMillis()));
    }
    private void seedMask(String name, String type, String pattern, String repl) {
        if (cnt("SELECT COUNT(*) FROM meta.sec_mask_rule WHERE name='" + name + "'") > 0) return;
        jdbc.update("INSERT INTO meta.sec_mask_rule(id, name, mask_type, pattern, replacement) VALUES (?,?,?,?,?)",
                System.currentTimeMillis() + (long) (Math.random() * 1000), name, type, pattern, repl);
    }

    private void seedLayer(String code, String name, int sort) {
        if (cnt("SELECT COUNT(*) FROM meta.gov_layer WHERE code='" + code + "'") > 0) return;
        jdbc.update("INSERT INTO meta.gov_layer(id, code, name, sort, status) VALUES (?,?,?,?,?)", System.currentTimeMillis() + sort, code, name, sort, "NORMAL");
    }

    private String kv(String k) {
        try { return jdbc.queryForObject("SELECT v FROM meta.sys_kv WHERE k=?", String.class, k); }
        catch (Exception e) { return null; }
    }

    private void kvSet(String k, String v) {
        try {
            int n = jdbc.update("UPDATE meta.sys_kv SET v=? WHERE k=?", v, k);
            if (n == 0) jdbc.update("INSERT INTO meta.sys_kv(k,v) VALUES (?,?)", k, v);
        } catch (Exception e) { /* ignore */ }
    }

    private void seed() {
        Timestamp now = new Timestamp(System.currentTimeMillis());

        // ---------- 租户 ----------
        tenant(1, "DATA", "数据集团总部", "NORMAL", now);
        tenant(2, "PARTNER", "合作研发中心", "NORMAL", now);

        // ---------- 组织（租户内树形） ----------
        org(10, 1, 0, "HQ", "集团总部", 1, now);
        org(11, 1, 10, "BIZ", "业务中心", 1, now);
        org(12, 1, 10, "GOV", "数据治理中心", 2, now);
        org(13, 1, 10, "IT", "信息技术与安全部", 3, now);
        org(20, 2, 0, "RD", "研发总部", 1, now);

        // ---------- 角色（三员） ----------
        role(1, "SYS_ADMIN", "系统管理员");
        role(2, "SEC_ADMIN", "安全保密管理员");
        role(3, "AUDIT_ADMIN", "安全审计员");

        // ---------- 菜单 ----------
        // 数据门户 + 系统管理目录 + 6 个系统子菜单
        menu(1, 0, "数据门户", "/portal", "DataBoard", "portal:view", "MENU", 1);
        menu(5, 0, "系统管理", "", "Setting", "", "CATALOG", 5);
        menu(6, 5, "用户管理", "/system/user", "User", "sys:user", "MENU", 1);
        menu(7, 5, "组织管理", "/system/org", "OfficeBuilding", "sys:org", "MENU", 2);
        menu(8, 5, "租户管理", "/system/tenant", "Files", "sys:tenant", "MENU", 3);
        menu(9, 5, "角色管理", "/system/role", "UserFilled", "sys:role", "MENU", 4);
        menu(10, 5, "菜单管理", "/system/menu", "Menu", "sys:menu", "MENU", 5);
        menu(11, 5, "日志管理", "/system/log", "Document", "sys:log", "MENU", 6);

        // 数据接入（一级目录 + 4 子菜单）
        menu(12, 0, "数据接入", "/data-access", "Coin", "", "CATALOG", 6);
        menu(13, 12, "数据源管理", "/data-access/source", "Connection", "da:source", "MENU", 1);
        menu(14, 12, "文件管理", "/data-access/file", "FolderOpened", "da:file", "MENU", 2);
        menu(15, 12, "离线数据接入", "/data-access/offline", "Download", "da:offline", "MENU", 3);
        menu(16, 12, "实时数据接入", "/data-access/stream", "VideoPlay", "da:stream", "MENU", 4);
        menu(17, 12, "数据探查", "/data-access/profile", "Search", "da:profile", "MENU", 5);

        // 数据治理（一级目录 + 7 子菜单）
        menu(18, 0, "数据治理", "/data-gov", "Filter", "", "CATALOG", 7);
        menu(19, 18, "数据标准", "/data-gov/std", "Coin", "gov:std", "MENU", 1);
        menu(20, 18, "数据模型", "/data-gov/model", "Share", "gov:model", "MENU", 2);
        menu(21, 18, "数据仓库", "/data-gov/wh", "Files", "gov:wh", "MENU", 3);
        menu(22, 18, "数据质量", "/data-gov/quality", "CircleCheck", "gov:quality", "MENU", 4);
        menu(23, 18, "元数据", "/data-gov/meta", "Document", "gov:meta", "MENU", 5);
        menu(24, 18, "数据标签", "/data-gov/tag", "PriceTag", "gov:tag", "MENU", 6);
        menu(25, 18, "主数据", "/data-gov/master", "Box", "gov:master", "MENU", 7);

        // 数据开发（一级目录 + 4 子菜单）
        menu(26, 0, "数据开发", "/data-dev", "Cpu", "", "CATALOG", 8);
        menu(27, 26, "函数管理", "/data-dev/func", "Operation", "dev:func", "MENU", 1);
        menu(28, 26, "数据开发", "/data-dev/script", "EditPen", "dev:script", "MENU", 2);
        menu(29, 26, "数据接出", "/data-dev/export", "Promotion", "dev:export", "MENU", 3);
        menu(30, 26, "工作流", "/data-dev/workflow", "Connection", "dev:workflow", "MENU", 4);

        // 数据资产（一级目录 + 3 子菜单）
        menu(31, 0, "数据资产", "/asset", "FolderOpened", "", "CATALOG", 9);
        menu(32, 31, "资产编目", "/asset/catalog", "Files", "asset:catalog", "MENU", 1);
        menu(33, 31, "资产挂载", "/asset/mount", "Link", "asset:mount", "MENU", 2);
        menu(34, 31, "资产审核", "/asset/audit", "Checked", "asset:audit", "MENU", 3);

        // 运维中心（一级目录 + 8 子菜单）
        menu(35, 0, "运维中心", "/ops", "Monitor", "", "CATALOG", 10);
        menu(36, 35, "交互式分析", "/ops/query", "Search", "ops:query", "MENU", 1);
        menu(37, 35, "数据概览", "/ops/overview", "DataBoard", "ops:overview", "MENU", 2);
        menu(38, 35, "任务中心", "/ops/tasks", "List", "ops:tasks", "MENU", 3);
        menu(39, 35, "任务概览", "/ops/task-stats", "TrendCharts", "ops:taskstats", "MENU", 4);
        menu(40, 35, "资源监控", "/ops/resource", "Odometer", "ops:resource", "MENU", 5);
        menu(41, 35, "集群管理", "/ops/cluster", "Cpu", "ops:cluster", "MENU", 6);
        menu(42, 35, "执行器管理", "/ops/executor", "Setting", "ops:executor", "MENU", 7);
        menu(43, 35, "连接器管理", "/ops/connector", "Connection", "ops:connector", "MENU", 8);

        // 数据安全（一级目录 + 7 子菜单）
        menu(44, 0, "数据安全", "/security", "Lock", "", "CATALOG", 11);
        menu(45, 44, "安全标准", "/security/std", "Medal", "sec:std", "MENU", 1);
        menu(46, 44, "数据脱敏", "/security/mask", "Hide", "sec:mask", "MENU", 2);
        menu(47, 44, "密钥管理", "/security/key", "Key", "sec:key", "MENU", 3);
        menu(48, 44, "告警管理", "/security/alert", "Bell", "sec:alert", "MENU", 4);
        menu(49, 44, "黑白名单", "/security/ip", "Failed", "sec:ip", "MENU", 5);
        menu(50, 44, "敏感数据", "/security/sensitive", "Warning", "sec:sensitive", "MENU", 6);
        menu(51, 44, "数据权限", "/security/perm", "Lock", "sec:perm", "MENU", 7);

        // 数据服务（一级目录 + 2 子菜单）
        menu(52, 0, "数据服务", "/dservice", "Share", "", "CATALOG", 12);
        menu(53, 52, "服务管理", "/dservice/service", "Connection", "ds:service", "MENU", 1);
        menu(54, 52, "调用统计", "/dservice/stats", "DataLine", "ds:stats", "MENU", 2);

        // 数据集市（数据门户/消费方门户，聚合数据服务开放资源+库表）
        menu(55, 0, "数据集市", "/market", "Goods", "", "CATALOG", 13);
        menu(56, 55, "数据集", "/market/dataset", "Files", "market:dataset", "MENU", 1);
        menu(57, 55, "资源概览", "/market/overview", "DataAnalysis", "market:overview", "MENU", 2);

        // 数据门户 → 数据总览（重命名，幂等 UPDATE）
        try { jdbc.update("UPDATE meta.sys_menu SET name='数据总览' WHERE id=1"); } catch (Exception ignored) {}
        // ---------- 角色-菜单授权（三员各管其域） ----------
        // SYS_ADMIN：业务 + 用户/组织/租户
        int[] sysMenus = {1, 5, 6, 7, 8};
        // SEC_ADMIN：业务 + 角色/菜单
        int[] secMenus = {1, 5, 9, 10};
        // AUDIT_ADMIN：业务 + 日志
        int[] audMenus = {1, 5, 11};
        for (int m : sysMenus) grantMenu(1, m);
        for (int m : secMenus) grantMenu(2, m);
        for (int m : audMenus) grantMenu(3, m);
        // 数据接入菜单授予 SYS_ADMIN（平台建设/数据接入归系统管理员）
        int[] daMenus = {12, 13, 14, 15, 16, 17};
        for (int m : daMenus) grantMenu(1, m);
        // 数据治理菜单授予 SYS_ADMIN
        int[] govMenus = {18, 19, 20, 21, 22, 23, 24, 25};
        for (int m : govMenus) grantMenu(1, m);
        // 数据开发菜单授予 SYS_ADMIN
        int[] devMenus = {26, 27, 28, 29, 30};
        for (int m : devMenus) grantMenu(1, m);
        // 数据资产菜单授予 SYS_ADMIN
        int[] assetMenus = {31, 32, 33, 34};
        for (int m : assetMenus) grantMenu(1, m);
        // 运维中心菜单授予 SYS_ADMIN
        int[] opsMenus = {35, 36, 37, 38, 39, 40, 41, 42, 43};
        for (int m : opsMenus) grantMenu(1, m);
        // 数据安全菜单授予 SYS_ADMIN
        int[] securityMenus = {44, 45, 46, 47, 48, 49, 50, 51};
        for (int m : securityMenus) grantMenu(1, m);
        // 数据服务菜单授予 SYS_ADMIN
        int[] dsvcMenus = {52, 53, 54};
        for (int m : dsvcMenus) grantMenu(1, m);
        // 数据集市菜单授予 SYS_ADMIN
        int[] marketMenus = {55, 56, 57};
        for (int m : marketMenus) grantMenu(1, m);

        // ---------- 用户（三员 + 超级演示号） ----------
        // admin/admin123：三员合一（便于演示全貌）
        user(1, "admin", "admin123", "超级管理员", "NORMAL", 1, 13, now);
        assignRole(1, 1); assignRole(1, 2); assignRole(1, 3);
        // 三员账号（真实分立：各持单一角色）
        user(100, "sysadmin", "sysadmin123", "系统管理员(张工)", "NORMAL", 1, 13, now);
        assignRole(100, 1);
        user(101, "secadmin", "secadmin123", "安全保密管理员(李工)", "NORMAL", 1, 13, now);
        assignRole(101, 2);
        user(102, "audadmin", "audadmin123", "安全审计员(王工)", "NORMAL", 1, 12, now);
        assignRole(102, 3);
    }

    // -------- 幂等插入助手（存在则跳过） --------

    private void tenant(long id, String code, String name, String status, Timestamp ts) {
        if (cnt("SELECT COUNT(*) FROM meta.sys_tenant WHERE id=" + id) > 0) return;
        jdbc.update("INSERT INTO meta.sys_tenant(id, code, name, status, create_time) VALUES (?,?,?,?,?)", id, code, name, status, ts);
    }

    private void org(long id, long tenantId, long parent, String code, String name, int sort, Timestamp ts) {
        if (cnt("SELECT COUNT(*) FROM meta.sys_org WHERE id=" + id) > 0) return;
        jdbc.update("INSERT INTO meta.sys_org(id, tenant_id, parent_id, code, name, sort, create_time) VALUES (?,?,?,?,?,?,?)", id, tenantId, parent, code, name, sort, ts);
    }

    private void role(long id, String code, String name) {
        if (cnt("SELECT COUNT(*) FROM meta.sys_role WHERE id=" + id) > 0) return;
        jdbc.update("INSERT INTO meta.sys_role(id, code, name) VALUES (?,?,?)", id, code, name);
    }

    private void menu(long id, long parent, String name, String path, String icon, String perm, String type, int sort) {
        if (cnt("SELECT COUNT(*) FROM meta.sys_menu WHERE id=" + id) > 0) return;
        jdbc.update("INSERT INTO meta.sys_menu(id, parent_id, name, path, icon, perm, type, sort) VALUES (?,?,?,?,?,?,?,?)", id, parent, name, path, icon, perm, type, sort);
    }

    private void grantMenu(long roleId, int menuId) {
        if (cnt("SELECT COUNT(*) FROM meta.sys_role_menu WHERE role_id=" + roleId + " AND menu_id=" + menuId) > 0) return;
        jdbc.update("INSERT INTO meta.sys_role_menu(role_id, menu_id) VALUES (?,?)", roleId, menuId);
    }

    private void user(long id, String username, String pwd, String name, String status, long tenantId, long orgId, Timestamp ts) {
        if (cnt("SELECT COUNT(*) FROM meta.sys_user WHERE username='" + username + "'") > 0) {
            // 已存在则补全租户/组织（旧 admin 升级）
            jdbc.update("UPDATE meta.sys_user SET name=?, tenant_id=?, org_id=? WHERE username=?", name, tenantId, orgId, username);
            return;
        }
        jdbc.update("INSERT INTO meta.sys_user(id, username, password, name, status, tenant_id, org_id, create_time) VALUES (?,?,?,?,?,?,?,?)",
                id, username, hash(pwd), name, status, tenantId, orgId, ts);
    }

    private void assignRole(long userId, long roleId) {
        if (cnt("SELECT COUNT(*) FROM meta.sys_user_role WHERE user_id=" + userId + " AND role_id=" + roleId) > 0) return;
        jdbc.update("INSERT INTO meta.sys_user_role(user_id, role_id) VALUES (?,?)", userId, roleId);
    }

    private String hash(String pwd) { return com.pharma.service.security.PasswordUtil.hash(pwd); }
    private long cnt(String sql) { try { return jdbc.queryForObject(sql, Long.class); } catch (Exception e) { return 0; } }
    private void exec(String sql) { try { jdbc.execute(sql); } catch (Exception e) { /* 幂等：列/表已存在等忽略 */ } }
}
