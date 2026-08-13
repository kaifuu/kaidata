<template>
  <div>
    <div class="dl-card">
      <div class="card-title">
        <span class="ct-left"><el-icon class="title-icon"><Connection /></el-icon>数据源管理</span>
        <div class="head-right">
          <span class="count-badge">共 <b>{{ filtered.length }}</b> 个数据源</span>
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新增数据源</el-button>
        </div>
      </div>

      <!-- 检索 -->
      <div class="dl-toolbar">
        <el-input v-model="kw" placeholder="名称关键字" size="small" clearable style="width:200px" />
        <el-select v-model="kwType" placeholder="全部类型" size="small" clearable filterable style="width:180px">
          <el-option v-for="t in types" :key="t.code" :label="t.code" :value="t.code" />
        </el-select>
        <div class="toolbar-actions">
          <span class="muted">客户端过滤</span>
        </div>
      </div>

      <el-table :data="paged" size="small" stripe border v-loading="loading">
        <el-table-column type="index" label="序号" width="70" :index="seqIndex" />
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <div class="ds-type">
              <span class="ds-type-name"><span class="ds-dot" :class="'g-' + groupKey(row.type)" />{{ typeLabel(row.type) }}</span>
              <span class="ds-type-grp">{{ groupLabel(row.type) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="连接地址" min-width="180">
          <template #default="{ row }">
            <span v-if="row.host">{{ row.host }}:{{ row.port }}<span v-if="row.db_name"> / {{ row.db_name }}</span></span>
            <span v-else class="muted">{{ addrPreview(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="账号" width="100" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'NORMAL' ? 'success' : 'warning'">{{ row.status === 'NORMAL' ? '正常' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button size="small" link type="success" :loading="testingId === row.id" @click="test(row)">测试</el-button>
              <el-button size="small" link type="primary" :disabled="!canBrowse(row.type)" @click="openWorkspace(row)">工作台</el-button>
              <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
              <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="filtered.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper"
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
      <div class="hint">
        <el-icon><InfoFilled /></el-icon>
        支持 24 类数据源（关系型 / 国产 / 大数据 / 消息 / 文件 / 缓存 / 对象存储 / 搜索）；<b>国产库与登记型类型</b>为占位，连通需放驱动或经对应管道；密码 AES-GCM 加密存储；<b>被引用时禁止删改连接信息</b>。
      </div>
    </div>

    <!-- 新增/编辑（按类型动态切换参数） -->
    <el-dialog v-model="dlg" :title="form.id ? '编辑数据源' : '新增数据源'" width="600px">
      <!-- 使用限制警示 -->
      <el-alert v-if="usages?.inUse" type="warning" :closable="false" show-icon style="margin-bottom:12px"
        :title="`数据源已被【${usages.modules.join('、')}】使用，连接 类型/地址/端口 不可修改`" />

      <el-form :model="form" label-width="96px" size="default">
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="自定义数据源名称" /></el-form-item>
        <el-form-item label="启停">
          <el-radio-group v-model="form.status">
            <el-radio value="NORMAL">开启</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
          <span class="muted" style="margin-left:12px">停用后不生效</span>
        </el-form-item>
        <el-form-item label="数据源类型">
          <el-select v-model="form.type" style="width:100%" filterable :disabled="connLocked" @change="onType">
            <el-option-group v-for="g in typeGroups" :key="g.label" :label="g.label">
              <el-option v-for="c in g.codes" :key="c" :label="c + typeBadge(c)" :value="c" />
            </el-option-group>
          </el-select>
        </el-form-item>

        <!-- 类型说明 -->
        <el-form-item v-if="spec.note" label=" ">
          <el-alert :title="spec.note" :type="spec.warn ? 'warning' : 'info'" :closable="false" show-icon style="width:100%" />
        </el-form-item>

        <!-- host/port：jdbc / es 显示 -->
        <template v-if="showHostPort">
          <el-form-item label="数据源 IP"><el-input v-model="form.host" :disabled="connLocked" placeholder="如 127.0.0.1" /></el-form-item>
          <el-form-item label="端口">
            <el-input-number v-model="form.port" :min="0" :max="65535" :disabled="connLocked" controls-position="right" style="width:100%" />
          </el-form-item>
        </template>

        <!-- 库名：jdbc 显示 -->
        <el-form-item v-if="showDb" :label="spec.dbLabel || '数据库名'">
          <el-input v-model="form.db_name" :placeholder="spec.dbHint" />
        </el-form-item>

        <!-- jdbc url 联动预览（只读） -->
        <el-form-item v-if="showJdbcUrl" label="JDBC URL">
          <el-input :model-value="jdbcUrl" readonly>
            <template #append><span class="muted">自动生成</span></template>
          </el-input>
        </el-form-item>

        <!-- 按类型动态结构化参数 -->
        <el-form-item v-for="f in paramFields" :key="f.key" :label="f.label">
          <el-switch v-if="f.type === 'switch'" v-model="extra[f.key]" />
          <el-input-number v-else-if="f.type === 'number'" v-model="extra[f.key]" controls-position="right" style="width:100%" />
          <el-select v-else-if="f.type === 'select'" v-model="extra[f.key]" style="width:100%">
            <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
          </el-select>
          <el-input v-else v-model="extra[f.key]" :type="f.inputType || 'text'" :placeholder="f.placeholder" />
        </el-form-item>

        <el-form-item label="账号"><el-input v-model="form.username" :placeholder="spec.group === 'es' ? 'Basic 认证用户名（可留空）' : '用户名（必须填写）'" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空则不修改' : (spec.group === 'es' ? 'Basic 认证密码（可留空）' : '请输入密码')" />
        </el-form-item>

        <!-- 高级：原始 props（只读展示 extra 序列化结果） -->
        <el-collapse>
          <el-collapse-item title="高级（原始扩展参数 JSON）">
            <el-input v-model="form.props" type="textarea" :rows="3" placeholder='留空则按上方结构化参数自动生成' />
            <div class="muted" style="margin-top:4px">结构化参数会自动序列化为此 JSON；如需手工覆盖可直接编辑。保存时以此框最终内容为准。</div>
          </el-collapse-item>
        </el-collapse>
      </el-form>

      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="success" :loading="testing" @click="testForm"><el-icon><Connection /></el-icon> 测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 数据源工作台（Navicat 风格对象浏览器） -->
    <el-dialog v-model="wsDlg" :title="`数据源工作台 · ${wsDs?.name || ''}`" fullscreen destroy-on-close class="ws-dialog" @closed="onWsClosed">
      <!-- 连接信息条 -->
      <div class="ws-connbar" v-if="wsDs">
        <span class="ws-conn-type"><span class="ds-dot" :class="'g-' + groupKey(wsDs.type)" />{{ typeLabel(wsDs.type) }}</span>
        <span class="ws-conn-sep">|</span>
        <span class="ws-conn-name">{{ wsDs.name }}</span>
        <template v-if="wsDs.host">
          <span class="ws-conn-sep">|</span>
          <span class="ws-conn-addr">{{ wsDs.host }}<span class="ws-port">:{{ wsDs.port }}</span><span v-if="wsDs.db_name" class="muted"> / {{ wsDs.db_name }}</span></span>
        </template>
        <span class="ws-conn-spacer" />
        <span class="ws-conn-pill" :class="wsDs.status === 'NORMAL' ? 'ok' : 'off'"><span class="ws-conn-led" />{{ wsDs.status === 'NORMAL' ? '已连接' : '已停用' }}</span>
      </div>

      <el-row :gutter="14" class="ws-body">
        <!-- 左：对象面板 -->
        <el-col :span="5">
          <div class="ws-side">
            <div class="ws-side-head"><el-icon><Coin /></el-icon>对象<span class="ws-side-cnt" v-if="!esMode && tables.length">{{ tableTree.length }} schema · {{ tables.length }} 表</span></div>
            <div class="ws-side-body">
              <el-input v-if="!esMode" v-model="treeKw" placeholder="过滤表名" size="small" clearable :prefix-icon="Search" class="ws-filter" />
              <div v-if="wsLoading" class="ws-tree-loading"><el-icon class="is-loading"><Loading /></el-icon> 加载对象…</div>
              <el-tree v-else-if="!esMode" ref="treeRef" :data="tableTree" :props="{ label: 'name' }" node-key="key"
                       :filter-node-method="filterNode" highlight-current default-expand-all :expand-on-click-node="false"
                       @node-click="onPickTable" class="ws-tree">
                <template #default="{ data }">
                  <span v-if="data.isSchema" class="ws-node ws-node-schema"><el-icon><Folder /></el-icon>{{ data.name }}<span class="ws-node-cnt">{{ data.children?.length || 0 }}</span></span>
                  <span v-else class="ws-node ws-node-table"><el-icon><Grid /></el-icon>{{ data.name }}</span>
                </template>
              </el-tree>
              <!-- ES：索引列表 -->
              <div v-else>
                <div class="muted ws-idx-head">索引（{{ tables.length }}）</div>
                <div class="ws-idx-list"><el-tag v-for="idx in tables" :key="idx" size="small" effect="plain">{{ idx }}</el-tag></div>
                <div v-if="!tables.length" class="muted ws-idx-empty">无索引或连通失败</div>
              </div>
            </div>
          </div>
        </el-col>

        <!-- 右：主区 -->
        <el-col :span="19">
          <div class="ws-main">
            <div v-if="esMode" class="ws-empty-state"><el-icon class="ws-empty-icon"><Coin /></el-icon><div>Elasticsearch 为 REST 索引型数据源，左侧可查看索引列表。</div></div>
            <div v-else-if="!selTable" class="ws-empty-state"><el-icon class="ws-empty-icon"><Grid /></el-icon><div>从左侧选择一张表，查看 数据 / 结构 / DDL / 查询。</div></div>
            <template v-else>
              <div class="ws-tablebar">
                <el-icon><Grid /></el-icon>
                <span class="ws-tablebar-schema" v-if="selTable.schema_name">{{ selTable.schema_name }}</span>
                <span class="ws-tablebar-arrow" v-if="selTable.schema_name">›</span>
                <span class="ws-tablebar-name">{{ selTable.name }}</span>
              </div>
              <el-tabs v-model="activeTab" class="ws-tabs">
                <!-- 数据 -->
                <el-tab-pane label="数据" name="data">
                  <div class="dl-toolbar">
                    <el-input v-model="whereStr" placeholder="WHERE 条件（可选），如 id > 100" size="small" style="width:360px"
                              clearable @keyup.enter="loadData(true)" />
                    <el-button size="small" type="primary" :loading="dataLoading" @click="loadData(true)">查询</el-button>
                    <span class="muted">共 {{ dataTotal == null ? '?' : dataTotal }} 行 · 第 {{ dataPage.page }} 页</span>
                  </div>
                  <el-table :data="dataRows" size="small" border max-height="50vh" v-loading="dataLoading">
                    <el-table-column v-for="c in dataCols" :key="c" :prop="c" :label="c" min-width="130" show-overflow-tooltip />
                  </el-table>
                  <div class="dl-pagination" v-if="dataTotal != null">
                    <el-pagination :current-page="dataPage.page" :page-size="dataPage.size" :total="dataTotal"
                      :page-sizes="[20,50,100,200]" layout="total,sizes,prev,pager,next,jumper"
                      @size-change="onDataSize" @current-change="onDataPage" />
                  </div>
                </el-tab-pane>
                <!-- 结构 -->
                <el-tab-pane label="结构" name="struct">
                  <el-table :data="structCols" size="small" border max-height="56vh" v-loading="structLoading">
                    <el-table-column prop="name" label="字段" min-width="160" />
                    <el-table-column prop="type" label="类型" min-width="120" />
                    <el-table-column prop="comment" label="备注" min-width="160" show-overflow-tooltip />
                    <el-table-column prop="pos" label="序号" width="70" />
                  </el-table>
                </el-tab-pane>
                <!-- DDL -->
                <el-tab-pane label="DDL" name="ddl">
                  <div class="dl-toolbar ws-ddlbar">
                    <span class="muted">{{ ddlSource === 'native' ? '来源：原生 SHOW CREATE TABLE' : '来源：按字段元数据重建' }}</span>
                    <span class="toolbar-actions"><el-button size="small" link :icon="CopyDocument" @click="copyText(ddlText)">复制</el-button></span>
                  </div>
                  <pre class="ws-ddl">{{ ddlText || '加载中…' }}</pre>
                </el-tab-pane>
                <!-- 查询 -->
                <el-tab-pane label="查询" name="query">
                  <el-input v-model="querySql" type="textarea" :rows="6" class="ws-sql" placeholder="输入 SQL 后执行（上限 1000 行）" />
                  <div class="dl-toolbar">
                    <el-button size="small" type="success" :loading="querying" @click="runQuery">执行</el-button>
                    <span class="muted" v-if="queryResult">{{ queryResult.status }} · {{ queryResult.rowsRead ?? 0 }} 行{{ queryResult.msg ? ' · ' + queryResult.msg : '' }}</span>
                  </div>
                  <el-table :data="queryResult?.rows || []" size="small" border max-height="36vh" v-if="queryResult?.rows?.length">
                    <el-table-column v-for="c in (queryResult?.columns || [])" :key="c" :prop="c" :label="c" min-width="130" show-overflow-tooltip />
                  </el-table>
                  <div v-else-if="queryResult && !(queryResult.rows || []).length" class="ws-result-empty muted">无数据</div>
                </el-tab-pane>
              </el-tabs>
            </template>
          </div>
        </el-col>
      </el-row>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled, Connection, Search, Loading, Folder, Grid, Coin, CopyDocument } from '@element-plus/icons-vue'
import { api, errMsg, type DataSourceRow, type DataSourceType, type DatasourceUsage } from '@/api'

// ===== 类型规格：默认端口 / 库名语义 / 分组 / 提示 =====
const DS_SPECS: Record<string, any> = {
  mysql:         { group: 'jdbc', port: 3306,  dbLabel: '数据库名',   dbHint: 'database',   internal: true },
  starrocks:     { group: 'jdbc', port: 9030,  dbLabel: '数据库名',   dbHint: 'ods / dwd',  internal: true },
  doris:         { group: 'jdbc', port: 9030,  dbLabel: '数据库名',   dbHint: 'internal',   internal: true },
  postgresql:    { group: 'jdbc', port: 5432,  dbLabel: '数据库名',   dbHint: 'postgres' },
  greenplum:     { group: 'jdbc', port: 5432,  dbLabel: '数据库名',   dbHint: 'postgres',   note: '基于 PostgreSQL 协议（驱动复用 pg）' },
  opengauss:     { group: 'jdbc', port: 5432,  dbLabel: '数据库名',   dbHint: 'postgres',   note: '华为高斯，基于 PostgreSQL 协议（驱动复用 pg）' },
  clickhouse:    { group: 'jdbc', port: 8123,  dbLabel: '数据库',     dbHint: 'default',    internal: true, note: '经 HTTP 接口接入（默认端口 8123）' },
  sqlserver:     { group: 'jdbc', port: 1433,  dbLabel: '数据库名',   dbHint: 'master' },
  oracle:        { group: 'jdbc', port: 1521,  dbLabel: '服务名/SID', dbHint: 'ORCL',       note: '服务名形式 host:port/服务名' },
  tdengine:      { group: 'jdbc', port: 6041,  dbLabel: '库名',       dbHint: 'test',       warn: true, note: 'TDengine 经 REST 接入（默认端口 6041）' },
  dameng:        { group: 'jdbc', port: 5236,  dbLabel: '库名',       dbHint: 'SYSDBA',     warn: true, note: '达梦，需手动放置 DmJdbcDriver18.jar 到 lib/ 并加 system scope 依赖' },
  kingbase:      { group: 'jdbc', port: 54321, dbLabel: '数据库名',   dbHint: 'test',       note: '人大金仓，兼容 PostgreSQL 协议，复用 pg 驱动接入' },
  gbase:         { group: 'jdbc', port: 5258,  dbLabel: '库名',       dbHint: 'test',       warn: true, note: '南大通用，需手动放置 gbase-connector-java.jar 到 lib/ 并加 system scope 依赖' },
  hive:          { group: 'jdbc', port: 10000, dbLabel: '数据库',     dbHint: 'default',    internal: true, warn: true, note: '需用 Maven profile with-hive 构建后才可连通' },
  elasticsearch: { group: 'es',   port: 9200,  note: 'REST 接口，按索引(Index)访问，无数据库概念；账号密码为 HTTP Basic 认证（可留空）' },
  kafka:         { group: 'mq',   port: 9092,  note: '消息总线，经「实时接入」Kafka 管道消费；数据源处仅做登记（测试不连通）' },
  ftp:           { group: 'file', port: 21,    note: 'FTP 文件协议，地址与 IP/端口不联动，请在结构化参数填地址；经「文件管理」接入' },
  sftp:          { group: 'file', port: 22,    note: 'SFTP 文件协议，地址与 IP/端口不联动；经「文件管理」接入' },
  ssh:           { group: 'file', port: 22,    note: 'SSH 远程文件通道，地址不联动；经「文件管理」接入' },
  redis:         { group: 'kv',   port: 6379,  note: '内存缓存，选模式（单机/集群）；登记型' },
  minio:         { group: 'obj',  port: 9000,  note: '对象存储，本项目经「文件管理」MinIO 存储接入；数据源处仅做登记' },
  hdfs:          { group: 'bigdata', port: 8020, note: '分布式存储，填 defaultFS 与路径；登记型' },
  mongodb:       { group: 'bigdata', port: 27017, note: '文档库，集群地址与 IP/端口不联动，按模板在结构化参数填写；登记型' },
  hbase:         { group: 'bigdata', port: 2181, note: '列式库，填 ZK 地址与 znode_parent；登记型' }
}

// 下拉分组
const typeGroups = [
  { label: '关系型数据库', codes: ['mysql','starrocks','doris','postgresql','greenplum','opengauss','clickhouse','sqlserver','oracle','tdengine'] },
  { label: '国产数据库', codes: ['dameng','kingbase','gbase'] },
  { label: '大数据', codes: ['hive','hbase','mongodb','hdfs'] },
  { label: '消息队列', codes: ['kafka'] },
  { label: '文件存储', codes: ['ftp','sftp','ssh'] },
  { label: '缓存', codes: ['redis'] },
  { label: '对象存储', codes: ['minio'] },
  { label: '搜索引擎', codes: ['elasticsearch'] }
]

// 按类型的结构化参数 schema
type FieldDef = { key: string; label: string; type: 'text'|'number'|'switch'|'select'; placeholder?: string; options?: string[]; inputType?: string }
const PARAM_SCHEMA: Record<string, FieldDef[]> = {
  postgresql:  [{ key: 'schema', label: 'Schema', type: 'text', placeholder: 'public' }],
  greenplum:   [{ key: 'schema', label: 'Schema', type: 'text', placeholder: 'public' }],
  opengauss:   [{ key: 'schema', label: 'Schema', type: 'text', placeholder: 'public' }],
  kingbase:    [{ key: 'schema', label: 'Schema', type: 'text', placeholder: 'public' }],
  oracle:      [{ key: 'schema', label: 'Schema(owner)', type: 'text', placeholder: '可选' }],
  gbase:       [{ key: 'gbaseserver', label: 'gbaseserver', type: 'text', placeholder: 'gbaseserver' }],
  clickhouse:  [{ key: 'cluster', label: '集群模式', type: 'switch' }, { key: 'clusterName', label: '集群名称', type: 'text', placeholder: '集群部署时填写' }],
  doris:       [{ key: 'httpPort', label: 'HTTP Port', type: 'text', placeholder: 'http://ip:port（提速大数据传输）' }],
  hive:        [
    { key: 'defaultFS', label: 'defaultFS', type: 'text', placeholder: 'hdfs://192.168.x.x:8020' },
    { key: 'hdfsPath', label: 'HDFS path', type: 'text', placeholder: '/warehouse/.../xxx.db' },
    { key: 'metastoreUri', label: 'Metastore uri', type: 'text', placeholder: 'thrift://192.168.x.x:9083' },
    { key: 'hudi', label: 'Hudi 表格式', type: 'switch' },
    { key: 'kerberos', label: 'Kerberos 认证', type: 'switch' }
  ],
  kafka:       [
    { key: 'bootstrap', label: 'bootstrap-servers', type: 'text', placeholder: '192.168.x.x:9092' },
    { key: 'zookeeper', label: 'zookeeper 地址', type: 'text', placeholder: '192.168.x.x:2181' },
    { key: 'authMechanism', label: '认证方式', type: 'select', options: ['无','SASL_PLAINTEXT','SASL_SSL'] }
  ],
  ftp:         [{ key: 'address', label: '地址', type: 'text', placeholder: 'ip:port（不联动）' }, { key: 'basePath', label: '路径', type: 'text', placeholder: '存在且有权限的路径' }, { key: 'charset', label: '字符集', type: 'text', placeholder: 'UTF-8' }],
  sftp:        [{ key: 'address', label: '地址', type: 'text', placeholder: 'ip:port（不联动）' }, { key: 'basePath', label: '路径', type: 'text', placeholder: '存在且有权限的路径' }, { key: 'charset', label: '字符集', type: 'text', placeholder: 'UTF-8' }],
  ssh:         [{ key: 'address', label: '地址', type: 'text', placeholder: 'ip:port（不联动）' }, { key: 'basePath', label: '路径', type: 'text' }, { key: 'charset', label: '字符集', type: 'text', placeholder: 'UTF-8' }],
  redis:       [{ key: 'mode', label: '模式', type: 'select', options: ['单机','集群'] }],
  minio:       [
    { key: 'endpoint', label: 'endpoint', type: 'text', placeholder: 'http://192.168.x.x:9000' },
    { key: 'accessKey', label: 'accessKey', type: 'text' },
    { key: 'secretKey', label: 'secretKey', type: 'text', inputType: 'password' },
    { key: 'bucket', label: 'bucket', type: 'text' }
  ],
  hdfs:        [{ key: 'defaultFS', label: 'defaultFS', type: 'text', placeholder: 'hdfs://192.168.x.x:8020' }, { key: 'basePath', label: '路径', type: 'text' }],
  mongodb:     [{ key: 'clusterUri', label: '集群地址', type: 'text', placeholder: 'mongodb://ip:port,ip:port/db（不联动）' }],
  hbase:       [{ key: 'zkQuorum', label: 'ZK 地址', type: 'text', placeholder: '192.168.x.x:2181' }, { key: 'znodeParent', label: 'znode_parent', type: 'text', placeholder: '/hbase' }]
}

// 类型友好显示名 + 分组（颜色点 + 类目副标题）
const TYPE_LABEL: Record<string, string> = {
  mysql: 'MySQL', starrocks: 'StarRocks', doris: 'Doris', postgresql: 'PostgreSQL', greenplum: 'Greenplum',
  opengauss: 'openGauss', clickhouse: 'ClickHouse', sqlserver: 'SQL Server', oracle: 'Oracle', tdengine: 'TDengine',
  dameng: '达梦', kingbase: '人大金仓', gbase: 'GBase', hive: 'Hive', hbase: 'HBase', mongodb: 'MongoDB', hdfs: 'HDFS',
  kafka: 'Kafka', ftp: 'FTP', sftp: 'SFTP', ssh: 'SSH', redis: 'Redis', minio: 'MinIO', elasticsearch: 'Elasticsearch'
}
const typeLabel = (t: string) => TYPE_LABEL[t] || (t ? t.charAt(0).toUpperCase() + t.slice(1) : '')
// 分组标签（由 typeGroups 反推）
const groupLabel = (t: string) => {
  for (const g of typeGroups) if (g.codes.includes(t)) return g.label
  return ''
}
const GROUP_KEY: Record<string, string> = {
  '关系型数据库': 'rel', '国产数据库': 'cn', '大数据': 'bd', '消息队列': 'mq',
  '文件存储': 'file', '缓存': 'kv', '对象存储': 'obj', '搜索引擎': 'es'
}
const groupKey = (t: string) => GROUP_KEY[groupLabel(t)] || 'rel'
const typeBadge = (c: string) => {
  const s = DS_SPECS[c]
  if (!s) return ''
  if (s.warn) return '（需配置/驱动）'
  if (s.group === 'mq' || s.group === 'file' || s.group === 'kv' || s.group === 'obj' || s.group === 'bigdata') return '（登记型）'
  return ''
}
// 可浏览源表的类型（jdbc + es）
const canBrowse = (t: string) => DS_SPECS[t]?.group === 'jdbc' || t === 'elasticsearch'

// ===== 状态 =====
const rows = ref<DataSourceRow[]>([])
const types = ref<DataSourceType[]>([])
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const testing = ref(false)
const testingId = ref<number | null>(null)
const kw = ref('')
const kwType = ref('')
const usages = ref<DatasourceUsage | null>(null)
const form = reactive<any>({ id: null, name: '', type: 'mysql', host: '127.0.0.1', port: 3306, db_name: '', username: '', password: '', props: '', status: 'NORMAL' })
const extra = reactive<Record<string, any>>({})

const spec = computed<any>(() => DS_SPECS[form.type] || { group: 'jdbc', port: 3306, dbLabel: '数据库名', dbHint: '' })
const paramFields = computed<FieldDef[]>(() => PARAM_SCHEMA[form.type] || [])
const showHostPort = computed(() => spec.value.group === 'jdbc' || spec.value.group === 'es')
const showDb = computed(() => spec.value.group === 'jdbc')
const showJdbcUrl = computed(() => spec.value.group === 'jdbc')
const connLocked = computed(() => !!usages.value?.inUse)

const jdbcUrl = computed(() => buildJdbcUrl(form.type, form.host, form.port, form.db_name))
function buildJdbcUrl(t: string, h: string, p: number, db: string): string {
  const host = h || '127.0.0.1', port = p || 0, d = db || ''
  switch (t) {
    case 'mysql': case 'starrocks': case 'doris':
      return `jdbc:mysql://${host}:${port}/${d}?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8`
    case 'postgresql': case 'greenplum': case 'opengauss': case 'kingbase':
      return `jdbc:postgresql://${host}:${port}/${d}`
    case 'clickhouse': return `jdbc:ch://${host}:${port}/${d}`
    case 'sqlserver': return `jdbc:sqlserver://${host}:${port};databaseName=${d};encrypt=false`
    case 'oracle': return `jdbc:oracle:thin:@${host}:${port}/${d}`
    case 'tdengine': return `jdbc:TAOS-RS://${host}:${port}/${d}`
    case 'hive': return `jdbc:hive2://${host}:${port}/${d}`
    default: return ''
  }
}

// 列表过滤 + 分页（客户端）
const filtered = computed(() => rows.value.filter(r =>
  (!kw.value || (r.name || '').toLowerCase().includes(kw.value.toLowerCase())) &&
  (!kwType.value || r.type === kwType.value)))
const page = reactive({ page: 1, size: 10 })
const paged = computed(() => filtered.value.slice((page.page - 1) * page.size, page.page * page.size))
function seqIndex(i: number) { return (page.page - 1) * page.size + i + 1 }
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
// 过滤条件变化时回到第一页，避免停留在空页
watch([kw, kwType], () => { page.page = 1 })

// 非 jdbc 类型的地址预览（从 props 解析）
function addrPreview(row: DataSourceRow): string {
  try {
    const p = row.props ? JSON.parse(row.props) : {}
    return p.bootstrap || p.address || p.endpoint || p.zkQuorum || p.defaultFS || p.clusterUri || '(登记型)'
  } catch { return '(登记型)' }
}

function resetExtra() {
  Object.keys(extra).forEach(k => delete extra[k])
  for (const f of paramFields.value) {
    extra[f.key] = f.type === 'switch' ? false : (f.type === 'select' ? (f.options as string[])[0] : '')
  }
}
function syncExtraFromProps(propsStr: string) {
  resetExtra()
  try {
    const p = propsStr ? JSON.parse(propsStr) : {}
    Object.keys(p).forEach(k => { extra[k] = p[k] })
  } catch { /* ignore */ }
}
function onType(t: string) {
  const s = DS_SPECS[t]
  if (s && showHostPort.value) form.port = s.port
  if (s?.group === 'es') form.db_name = ''
  resetExtra()
}

// ===== 工作台（Navicat 风格对象浏览器） =====
const wsDlg = ref(false); const wsDs = ref<DataSourceRow | null>(null); const wsLoading = ref(false)
const tables = ref<any[]>([])                 // daSourceTables 结果（ES 时为 indices）
const esMode = ref(false)
const treeRef = ref<any>(null); const treeKw = ref('')
const selTable = ref<any | null>(null)        // { name, schema_name }
const activeTab = ref('data')
// 数据 Tab
const dataCols = ref<string[]>([]); const dataRows = ref<any[]>([]); const dataTotal = ref<number | null>(null)
const dataLoading = ref(false); const dataPage = reactive({ page: 1, size: 50 }); const whereStr = ref('')
// 结构 Tab
const structCols = ref<any[]>([]); const structLoading = ref(false)
// DDL Tab
const ddlText = ref(''); const ddlSource = ref('')
// 查询 Tab
const querySql = ref(''); const queryResult = ref<any | null>(null); const querying = ref(false)

// 表树：按 schema_name 分组（顶层=schema，子=表）
const tableTree = computed(() => {
  const groups: Record<string, any[]> = {}
  for (const t of tables.value) {
    const sch = t.schema_name || '(default)'
    ;(groups[sch] = groups[sch] || []).push(t)
  }
  return Object.keys(groups).sort().map(sch => ({
    key: 's:' + sch, name: sch, isSchema: true,
    children: (groups[sch] || []).map((t: any) => ({
      key: 't:' + sch + ':' + t.name, name: t.name, schema_name: t.schema_name
    }))
  }))
})
function filterNode(value: string, data: any) {
  if (!value) return true
  if (data.isSchema) return true   // schema 节点保留（其命中的子节点会显示）
  return (data.name || '').toLowerCase().includes(value.toLowerCase())
}
watch(treeKw, (v) => treeRef.value?.filter(v))

async function load() {
  loading.value = true
  try {
    const [r, t] = await Promise.all([api.daSources(), api.daSourceTypes()])
    rows.value = r; types.value = t
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}

async function open(row?: DataSourceRow) {
  Object.assign(form, { id: null, name: '', type: 'mysql', host: '127.0.0.1', port: 3306, db_name: '', username: '', password: '', props: '', status: 'NORMAL' })
  usages.value = null
  resetExtra()
  if (row) {
    Object.assign(form, { id: row.id, name: row.name, type: row.type, host: row.host || '127.0.0.1', port: row.port || DS_SPECS[row.type]?.port || 0, db_name: row.db_name || '', username: row.username || '', props: row.props || '', status: row.status, password: '' })
    syncExtraFromProps(row.props || '')
    // 编辑时查使用情况
    try { usages.value = await api.daSourceUsages(row.id) } catch { usages.value = null }
  }
  dlg.value = true
}

async function save() {
  if (!form.name || !form.type) return ElMessage.warning('请填写名称与类型')
  // 结构化参数 → props（若用户未手工改高级区）
  form.props = JSON.stringify(extra)
  saving.value = true
  try { await api.daSaveSource({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: DataSourceRow) {
  try {
    await ElMessageBox.confirm(`确定删除数据源 ${row.name}？`, '提示', { type: 'warning' })
  } catch { return }
  try { await api.daDeleteSource(row.id); ElMessage.success('已删除'); await load() }
  catch (e) { ElMessage.error(errMsg(e)) }
}

async function test(row: DataSourceRow) {
  testingId.value = row.id
  try {
    const r: any = await api.daTestSource({ id: row.id })
    if (r.ok) ElMessageBox.alert(`连通成功（${r.latency}ms）<br/>${r.product} ${r.version || ''}`, '测试结果', { dangerouslyUseHTMLString: true, type: 'success' })
    else ElMessageBox.alert(r.msg || '连通失败', '测试结果', { type: 'error' })
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { testingId.value = null }
}

async function testForm() {
  form.props = JSON.stringify(extra)
  testing.value = true
  try {
    const r: any = await api.daTestSource({ ...form })
    if (r.ok) ElMessage.success(`连通成功（${r.latency}ms）${r.product || ''}`)
    else ElMessageBox.alert(r.msg || '连通失败', '测试结果', { type: 'error' })
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { testing.value = false }
}

function onWsClosed() {
  tables.value = []; selTable.value = null
  dataCols.value = []; dataRows.value = []; dataTotal.value = null
  structCols.value = []; ddlText.value = ''; ddlSource.value = ''; queryResult.value = null; whereStr.value = ''
}

async function openWorkspace(row: DataSourceRow) {
  wsDs.value = row
  esMode.value = row.type === 'elasticsearch'
  selTable.value = null
  treeKw.value = ''
  wsDlg.value = true
  wsLoading.value = true
  tables.value = []
  try {
    const res: any = await api.daSourceTables(row.id)
    tables.value = esMode.value ? (res?.indices || []) : (Array.isArray(res) ? res : [])
  } catch (e: any) { tables.value = []; ElMessage.error(errMsg(e, '该数据源驱动可能未就绪或无法列源表')) }
  finally { wsLoading.value = false }
}

function onPickTable(node: any) {
  if (node.isSchema || !wsDs.value) return
  selTable.value = node
  activeTab.value = 'data'
  dataPage.page = 1
  const fq = node.schema_name ? `${node.schema_name}.${node.name}` : node.name
  querySql.value = `SELECT * FROM ${fq} LIMIT 100`
  queryResult.value = null
  loadStruct()
  loadData(false)
  loadDdl()
}

async function loadData(resetPage: boolean) {
  if (!wsDs.value || !selTable.value) return
  if (resetPage) dataPage.page = 1
  dataLoading.value = true
  try {
    const res: any = await api.daSourceData(wsDs.value.id, selTable.value.schema_name, selTable.value.name, dataPage.page, dataPage.size, whereStr.value || undefined)
    dataCols.value = res.columns || []; dataRows.value = res.rows || []; dataTotal.value = res.total == null ? null : Number(res.total)
  } catch (e: any) { dataRows.value = []; dataTotal.value = null; ElMessage.error(errMsg(e, '数据查询失败')) }
  finally { dataLoading.value = false }
}
function onDataSize(s: number) { dataPage.size = s; dataPage.page = 1; loadData(false) }
function onDataPage(p: number) { dataPage.page = p; loadData(false) }

async function loadStruct() {
  if (!wsDs.value || !selTable.value) return
  structLoading.value = true
  try { structCols.value = await api.daSourceColumns(wsDs.value.id, selTable.value.name, selTable.value.schema_name) }
  catch (e: any) { structCols.value = [] }
  finally { structLoading.value = false }
}

async function loadDdl() {
  if (!wsDs.value || !selTable.value) return
  ddlText.value = ''; ddlSource.value = ''
  try {
    const res: any = await api.daSourceDdl(wsDs.value.id, selTable.value.schema_name, selTable.value.name)
    ddlText.value = res.ddl || ''; ddlSource.value = res.source || ''
  } catch (e: any) { ddlText.value = '-- 获取失败：' + errMsg(e); ddlSource.value = 'reconstructed' }
}

async function runQuery() {
  if (!wsDs.value || !querySql.value.trim()) return
  querying.value = true
  try {
    const res: any = await api.opsQuery({ datasource_id: wsDs.value.id, content: querySql.value })
    queryResult.value = res
    if (res.status && res.status !== 'SUCCESS') ElMessage.warning(res.msg || '执行完成但有提示')
  } catch (e: any) { ElMessage.error(errMsg(e)) }
  finally { querying.value = false }
}

async function copyText(t: string) {
  try { await navigator.clipboard.writeText(t || ''); ElMessage.success('已复制到剪贴板') }
  catch { ElMessage.warning('复制失败，请手动选择文本') }
}

onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.ct-left { display: inline-flex; align-items: center; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.ds-type { display: flex; flex-direction: column; gap: 2px; line-height: 1.3; }
.ds-type-name { display: inline-flex; align-items: center; gap: 6px; font-weight: 600; font-size: 13px; }
.ds-type-grp { font-size: 11px; color: var(--tech-text-muted); margin-left: 13px; }
.ds-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; flex-shrink: 0; }
.ds-dot.g-rel { background: #409eff; }
.ds-dot.g-cn  { background: #e6a23c; }
.ds-dot.g-bd  { background: #f56c6c; }
.ds-dot.g-mq  { background: #9254de; }
.ds-dot.g-file{ background: #13c2c2; }
.ds-dot.g-kv  { background: #ff7a45; }
.ds-dot.g-obj { background: #36cfc9; }
.ds-dot.g-es  { background: #722ed1; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.hint b { color: var(--tech-primary); }

/* ===== 工作台（Navicat 风格对象浏览器） ===== */
.ws-body { height: 78vh; }

/* 连接信息条 */
.ws-connbar {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 14px; margin-bottom: 12px; border-radius: 10px; font-size: 13px;
  background: var(--tech-panel); border: 1px solid var(--tech-panel-border); box-shadow: var(--tech-shadow);
}
.ws-conn-type { display: inline-flex; align-items: center; gap: 7px; font-weight: 600; color: var(--tech-text); }
.ws-conn-type .ds-dot { width: 9px; height: 9px; }
.ws-conn-sep { color: var(--tech-text-muted); opacity: .4; }
.ws-conn-name { color: var(--tech-text); font-weight: 500; }
.ws-conn-addr { font-family: ui-monospace, Menlo, monospace; color: var(--tech-text); }
.ws-port { color: var(--tech-text-muted); }
.ws-conn-spacer { flex: 1; }
.ws-conn-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 2px 10px; border-radius: 10px; font-weight: 500; }
.ws-conn-led { width: 6px; height: 6px; border-radius: 50%; }
.ws-conn-pill.ok { color: var(--tech-success); background: color-mix(in srgb, var(--tech-success) 14%, transparent); }
.ws-conn-pill.ok .ws-conn-led { background: var(--tech-success); box-shadow: 0 0 6px var(--tech-success); }
.ws-conn-pill.off { color: var(--tech-warn); background: color-mix(in srgb, var(--tech-warn) 14%, transparent); }
.ws-conn-pill.off .ws-conn-led { background: var(--tech-warn); }

/* 左侧对象面板 */
.ws-side { height: 78vh; display: flex; flex-direction: column; overflow: hidden;
  background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); }
.ws-side-head { display: flex; align-items: center; gap: 6px; padding: 10px 12px;
  font-weight: 600; font-size: 13px; color: var(--tech-text);
  border-bottom: 1px solid var(--tech-panel-border); background: var(--el-fill-color-light); }
.ws-side-head .el-icon { color: var(--tech-primary); }
.ws-side-cnt { margin-left: auto; font-size: 11px; font-weight: 400; color: var(--tech-text-muted); }
.ws-side-body { flex: 1; overflow: auto; padding: 10px; }
.ws-filter { margin-bottom: 8px; }
.ws-tree-loading { color: var(--tech-text-muted); padding: 14px 0; display: inline-flex; align-items: center; gap: 6px; }
.ws-idx-head { margin: 4px 0 8px; font-size: 12px; }
.ws-idx-list { display: flex; flex-wrap: wrap; gap: 6px; }
.ws-idx-empty { padding: 16px 0; }

/* 树节点 */
.ws-tree { background: transparent; }
.ws-node { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; line-height: 1; }
.ws-node .el-icon { font-size: 15px; flex-shrink: 0; }
.ws-node-schema { font-weight: 600; color: var(--tech-text); }
.ws-node-schema .el-icon { color: var(--tech-primary); }
.ws-node-table { color: var(--tech-text); }
.ws-node-table .el-icon { color: var(--tech-text-muted); }
.ws-node-cnt { margin-left: 2px; color: var(--tech-text-muted); font-weight: 400; font-size: 11px;
  background: var(--el-fill-color); padding: 0 6px; border-radius: 8px; line-height: 16px; }
.ws-tree :deep(.el-tree-node__content) { height: 30px; border-radius: 6px; margin-bottom: 1px; }
.ws-tree :deep(.el-tree-node__content:hover) { background: var(--el-fill-color-light); }
.ws-tree :deep(.el-tree-node.is-current > .el-tree-node__content) { background: color-mix(in srgb, var(--tech-primary) 14%, transparent); }
.ws-tree :deep(.el-tree-node.is-current > .el-tree-node__content .ws-node-table),
.ws-tree :deep(.el-tree-node.is-current > .el-tree-node__content .ws-node-table .el-icon) { color: var(--tech-primary); font-weight: 600; }

/* 右侧主区 */
.ws-main { height: 78vh; display: flex; flex-direction: column; min-width: 0; }
.ws-tablebar { display: inline-flex; align-items: center; gap: 8px; padding: 2px 2px 12px; font-size: 14px; color: var(--tech-text); }
.ws-tablebar > .el-icon { color: var(--tech-primary); font-size: 17px; }
.ws-tablebar-schema { color: var(--tech-text-muted); font-size: 13px; }
.ws-tablebar-arrow { color: var(--tech-text-muted); opacity: .55; }
.ws-tablebar-name { font-weight: 700; letter-spacing: .3px; }
.ws-tabs { flex: 1; min-height: 0; }
.ws-empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px;
  height: 78vh; color: var(--tech-text-muted); font-size: 13px; text-align: center; }
.ws-empty-icon { font-size: 46px; color: color-mix(in srgb, var(--tech-primary) 38%, transparent); }
.ws-result-empty { padding: 28px 0; text-align: center; }
.ws-ddlbar { margin-bottom: 10px; }
.ws-ddl { background: var(--tech-bg-2, var(--el-bg-color)); border: 1px solid var(--tech-panel-border, var(--el-border-color));
  border-radius: 8px; padding: 14px 16px; max-height: 58vh; overflow: auto; margin: 0;
  font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12.5px; line-height: 1.7;
  white-space: pre-wrap; word-break: break-word; color: var(--tech-text); }
:deep(.ws-sql textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 13px; line-height: 1.6; }
</style>
