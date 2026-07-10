package com.pharma.service.access.develop.job;

import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kettle/Hop 图形化作业（job_type=kettle_hop）：把 DAG 翻译成 Hop .hpl，通过 docker exec 调容器内
 * hop-run.sh CLI 进程内执行（Hop Server REST addPipeline servlet 实现有 STREAMED bug，改走 CLI）。
 * <p>流程：解析 dag_json → DagGraph.topoSort → DataSourceLoader.load → KettleXmlBuilder.buildPipeline
 * → 写临时 .hpl → docker cp 到容器 → docker exec hop-run.sh -f= -j=samples -r=local → 解析 stdout 行数/错误。
 * <p>取代旧版（调 /hop/execute REST，已 404）。让位前的 KettleSqlExecutor（SQL 翻译）降为 kettle_sql_fallback。
 */
@Component
public class KettleHopExecutor extends AbstractHttpExecutor {

    @Autowired private DataSourceLoader dsLoader;

    private static final String CONTAINER_DIR = "/opt/hop/config/projects/samples/transforms";
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    @Override public String jobType() { return "kettle_hop"; }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> execute(long taskId, Map<String, Object> task, StringBuilder log) throws Exception {
        // 1. 解析 dag_json
        long dsId = lng(task.get("datasource_id"));
        if (dsId <= 0) throw new RuntimeException("Kettle 作业未绑定执行数据源（datasource_id）");
        Map<String, Object> dag = parseJson(str(task.get("dag_json")));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dag.getOrDefault("nodes", Collections.emptyList());
        List<Map<String, Object>> edges = (List<Map<String, Object>>) dag.getOrDefault("edges", Collections.emptyList());
        if (nodes.isEmpty()) throw new RuntimeException("DAG 没有节点");
        DagGraph.topoSort(nodes, edges);  // 拓扑校验 + 检测环

        // 2. 数据源
        DataSourceDescriptor ds = dsLoader.load(dsId);
        log.append("数据源: ").append(ds.name).append(" (").append(ds.type)
           .append(" @ ").append(ds.host).append(":").append(ds.port).append("/").append(ds.dbName).append(")\n");

        // 3. 生成 .hpl XML + 外置连接 metadata json（Hop 不读 .hpl 内嵌 connection）
        String connName = "ds_" + taskId;
        String hpl = KettleXmlBuilder.buildPipeline("task_" + taskId, nodes, edges, ds, connName);
        String connJson = KettleXmlBuilder.buildConnectionJson(connName, ds);
        log.append("生成 .hpl + 连接 metadata json\n");
        String tmpDir = System.getProperty("java.io.tmpdir");

        // 4. 写连接 json + docker cp 到 metadata/rdbms/
        String localJson = Path.of(tmpDir, "hop_conn_" + taskId + ".json").toString();
        Files.writeString(Path.of(localJson), connJson, StandardCharsets.UTF_8);
        String containerJson = "/opt/hop/config/projects/samples/metadata/rdbms/" + connName + ".json";
        runCmd("docker", "cp", localJson, "pharma-hop:" + containerJson);

        // 5. 写 .hpl + docker cp
        String localFile = Path.of(tmpDir, "hop_task_" + taskId + ".hpl").toString();
        Files.writeString(Path.of(localFile), hpl, StandardCharsets.UTF_8);
        String containerFile = CONTAINER_DIR + "/dyn_" + taskId + ".hpl";
        log.append("docker cp .hpl → ").append(containerFile).append("\n");
        runCmd("docker", "cp", localFile, "pharma-hop:" + containerFile);

        // 6. docker exec hop-run.sh 执行（2>&1 合并 stderr，否则 Hop Java 日志丢失）
        log.append("--- Hop 执行 ---\n");
        String output = runCmd("docker", "exec", "pharma-hop", "sh", "-c",
                "/opt/hop/hop-run.sh -f=" + containerFile + " -j=samples -r=local -l=BASIC 2>&1");
        // 截取尾部日志（避免超 65000）
        String tail = output.length() > 4000 ? output.substring(output.length() - 4000) : output;
        log.append(tail).append("\n");

        // 7. 清理容器内临时文件（忽略失败）
        try { runCmd("docker", "exec", "pharma-hop", "rm", "-f", containerFile, containerJson); } catch (Exception ignored) {}
        Files.deleteIfExists(Path.of(localFile));
        Files.deleteIfExists(Path.of(localJson));

        // 8. 解析结果 + 严格错误判断
        long rowsWritten = parseLastLong(output, "W=(\\d+)");
        long errors = parseMaxLong(output, "E=(\\d+)");
        log.append("写入行数: ").append(rowsWritten).append(", 错误数: ").append(errors).append("\n");
        if (errors > 0 || output.contains("Exception") || output.contains("Error found")
                || output.contains("failed to initialize") || output.contains("ExecutionException")
                || output.isBlank() || !output.contains("HopRun exit")) {
            throw new RuntimeException("Hop 执行失败（错误行 " + errors + "），详见日志:\n" + output);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engineJobId", "");
        out.put("rowsRead", rowsWritten);
        return out;
    }

    /** 执行命令，双线程读 stdout+stderr 合并（避免 readAllBytes 阻塞丢输出）。超时抛异常。 */
    private String runCmd(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        Thread t1 = new Thread(() -> readStream(p.getInputStream(), out));
        Thread t2 = new Thread(() -> readStream(p.getErrorStream(), out));
        t1.start(); t2.start();
        boolean done = p.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        t1.join(3000); t2.join(3000);
        if (!done) { p.destroyForcibly(); throw new RuntimeException("命令超时: " + String.join(" ", cmd)); }
        return out.toString();
    }
    private static void readStream(java.io.InputStream is, StringBuilder sb) {
        try { byte[] b = is.readAllBytes(); sb.append(new String(b, StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    /** 取所有匹配中最后一个的数字（如 W= 行数，sink 通常最后）。 */
    private long parseLastLong(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        long last = 0;
        while (m.find()) last = Long.parseLong(m.group(1));
        return last;
    }
    /** 取所有匹配中最大的数字（如 E= 错误数，任意非0即错）。 */
    private long parseMaxLong(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        long max = 0;
        while (m.find()) max = Math.max(max, Long.parseLong(m.group(1)));
        return max;
    }

    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
