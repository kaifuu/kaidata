package com.pharma.batch;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;

import java.util.Properties;

/**
 * 数仓分层加工 Spark 作业（批计算）
 * <p>
 * 数据流：Doris ODS(原始) → Spark 分层加工 → DWD/DWS/ADS
 *   1. DWD：ods_batch ⋈ ods_qc ⋈ dim_material → dwd_batch_qc（明细宽表，关联物料名）
 *   2. ADS：由 DWD 裁剪 → ads_batch_quality（批次质量全景，供批次追溯）
 *   3. DWS/ADS：按物料汇总产量/异常/合格率 → dws_material_quality + ads_production_efficiency（供效能看板）
 *   4. ADS：ods_env_monitor 按设备/指标汇总达标率 → ads_env_compliance（供看板）
 * <p>
 * 批次与检验用 LEFT JOIN：即使某批次暂无检验结果也保留，保证追溯列表不丢批次。
 * 这是大数据平台"批计算"的核心：由 Spark 引擎做大规模分层加工，而非应用层 CRUD。
 * <p>
 * 提交示例（在 compose 网络内运行，可解析 starrocks 主机名）：
 *   docker run --rm --network=pharma-bigdata_default \
 *     -v /f/01_code/01_datalake/datalake-batch/target:/app \
 *     docker.1ms.run/apache/spark:3.5.1 \
 *     /opt/spark/bin/spark-submit --class com.pharma.batch.WarehouseBuildJob \
 *     --jars /app/mysql-connector-j-8.0.33.jar /app/datalake-batch.jar
 */
public class WarehouseBuildJob {

    public static void main(String[] args) throws Exception {
        String dorisHost = arg(args, "doris", "starrocks");
        String dorisUrl = "jdbc:mysql://" + dorisHost + ":9030/?useSSL=false&allowPublicKeyRetrieval=true";
        String user = arg(args, "user", "root");
        String pwd = arg(args, "pwd", "");

        SparkSession spark = SparkSession.builder()
                .appName("PharmaWarehouseBuild")
                .getOrCreate();

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", pwd);
        props.setProperty("driver", "com.mysql.cj.jdbc.Driver");

        // ---------- 读 ODS + DIM ----------
        Dataset<Row> batch = spark.read().jdbc(dorisUrl, "ods.ods_batch", props);
        Dataset<Row> qc = spark.read().jdbc(dorisUrl, "ods.ods_qc", props);
        Dataset<Row> env = spark.read().jdbc(dorisUrl, "ods.ods_env_monitor", props);
        Dataset<Row> material = spark.read().jdbc(dorisUrl, "dim.dim_material", props)
                .select("material_code", "material_name");

        // ---------- 1. DWD：批次 × 检验 × 物料维度 明细宽表 ----------
        Dataset<Row> dwd = batch.join(qc, "batch_no", "left")
                .join(material, "material_code", "left")
                .select(
                        batch.col("batch_no"),
                        batch.col("material_code"),
                        functions.coalesce(material.col("material_name"), batch.col("material_code")).as("material_name"),
                        batch.col("quantity"),
                        batch.col("status").as("batch_status"),
                        qc.col("test_item"),
                        qc.col("result"),
                        qc.col("spec"),
                        qc.col("pass").as("qc_pass"),
                        batch.col("ts")
                );
        dwd.write().mode("overwrite").option("truncate", "true").jdbc(dorisUrl, "dwd.dwd_batch_qc", props);
        System.out.println("[Batch] dwd_batch_qc 写入完成，行数=" + dwd.count());

        // ---------- 2. ADS：批次质量全景（裁剪自 DWD，供批次追溯） ----------
        Dataset<Row> adsBatch = dwd.select(
                dwd.col("batch_no"),
                dwd.col("material_code"),
                dwd.col("quantity"),
                dwd.col("batch_status"),
                dwd.col("qc_pass"),
                dwd.col("result").as("qc_result"),
                dwd.col("spec").as("qc_spec"),
                dwd.col("ts")
        );
        adsBatch.write().mode("overwrite").option("truncate", "true").jdbc(dorisUrl, "ads.ads_batch_quality", props);
        System.out.println("[Batch] ads_batch_quality 写入完成，行数=" + adsBatch.count());

        // ---------- 3. DWS / ADS：按物料汇总产能与质量 ----------
        // 批次维度：批次数 / 异常数 / 总产量（ods_batch 一批一行，sum 正确）
        Dataset<Row> batchAgg = batch.groupBy("material_code").agg(
                functions.countDistinct("batch_no").cast("long").as("total_batches"),
                functions.sum(functions.when(batch.col("status").equalTo("ABNORMAL"), 1).otherwise(0)).cast("long").as("abnormal_batches"),
                functions.sum("quantity").cast("long").as("total_quantity")
        );
        // 检验维度：检验次数 / 合格数 / 平均检验值
        Dataset<Row> qcAgg = qc.groupBy("material_code").agg(
                functions.count("batch_no").as("qc_test_cnt"),
                functions.sum(qc.col("pass").cast("int")).as("qc_pass_cnt"),
                functions.avg("result").as("avg_qc_result")
        );

        Dataset<Row> joined = batchAgg.join(qcAgg, "material_code", "left")
                .join(material, "material_code", "left")
                .na().fill(0, new String[]{"qc_test_cnt", "qc_pass_cnt"});

        Dataset<Row> dws = joined.select(
                joined.col("material_code"),
                functions.coalesce(joined.col("material_name"), joined.col("material_code")).as("material_name"),
                joined.col("total_batches"),
                joined.col("abnormal_batches"),
                joined.col("total_quantity"),
                functions.round(joined.col("avg_qc_result"), 2).as("avg_qc_result"),
                functions.when(joined.col("qc_test_cnt").gt(0),
                                functions.round(joined.col("qc_pass_cnt").divide(joined.col("qc_test_cnt")).multiply(100), 2))
                        .otherwise(0.0).as("pass_rate")
        );
        dws.write().mode("overwrite").option("truncate", "true").jdbc(dorisUrl, "dws.dws_material_quality", props);
        dws.write().mode("overwrite").option("truncate", "true").jdbc(dorisUrl, "ads.ads_production_efficiency", props);
        System.out.println("[Batch] dws_material_quality / ads_production_efficiency 写入完成，行数=" + dws.count());

        // ---------- 4. ADS：环境合规达标率（按设备/指标汇总） ----------
        Dataset<Row> adsEnv = env.select(
                        env.col("device_id"),
                        env.col("metric"),
                        env.col("value"),
                        env.col("min_val"),
                        env.col("max_val")
                )
                .groupBy("device_id", "metric")
                .agg(
                        functions.count("*").as("total_cnt"),
                        functions.sum(
                                functions.when(
                                        env.col("value").geq(env.col("min_val"))
                                                .and(env.col("value").leq(env.col("max_val"))),
                                        1
                                ).otherwise(0)
                        ).as("ok_cnt")
                )
                .withColumn("compliance_rate",
                        functions.round(
                                functions.col("ok_cnt")
                                        .divide(functions.col("total_cnt"))
                                        .multiply(100), 2));
        adsEnv.write().mode("overwrite").option("truncate", "true").jdbc(dorisUrl, "ads.ads_env_compliance", props);
        System.out.println("[Batch] ads_env_compliance 写入完成，行数=" + adsEnv.count());

        spark.stop();
    }

    private static String arg(String[] args, String key, String def) {
        for (String a : args) {
            if (a.startsWith("--" + key + "=")) return a.substring(key.length() + 3);
        }
        return def;
    }
}
