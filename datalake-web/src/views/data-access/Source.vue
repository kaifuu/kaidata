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

      <el-table :data="filtered" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="130" />
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.type)">{{ row.type }}</el-tag>
            <el-tag v-if="row.internal" size="small" type="success" effect="plain" style="margin-left:4px">内部</el-tag>
            <el-tag v-else size="small" type="info" effect="plain" style="margin-left:4px">外部</el-tag>
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
              <el-button size="small" link type="primary" :disabled="!canBrowse(row.type)" @click="openTables(row)">源表</el-button>
              <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
              <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
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

    <!-- 源表浏览 -->
    <el-dialog v-model="tableDlg" :title="`源表浏览 - ${current?.name || ''}`" width="720px">
      <el-table v-if="esMode === false" :data="tables" size="small" border v-loading="tableLoading" max-height="420">
        <el-table-column prop="name" label="表名" min-width="200" />
        <el-table-column prop="schema_name" label="Schema" width="140" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }"><el-button link type="primary" size="small" @click="openCols(row)">查看字段</el-button></template>
        </el-table-column>
      </el-table>
      <div v-else-if="esMode" v-loading="tableLoading">
        <div class="muted" style="margin-bottom:6px">索引列表（共 {{ tables.length }}）</div>
        <el-tag v-for="idx in tables" :key="idx" style="margin:0 6px 6px 0">{{ idx }}</el-tag>
        <div v-if="!tables.length" class="muted">无索引或连通失败</div>
      </div>
      <div v-if="cols.length && esMode === false" style="margin-top:12px">
        <div class="muted" style="margin-bottom:6px">字段（{{ colsTableName }}）</div>
        <el-table :data="cols" size="small" border max-height="240">
          <el-table-column prop="name" label="字段" min-width="160" />
          <el-table-column prop="type" label="类型" min-width="120" />
          <el-table-column prop="comment" label="备注" min-width="120" />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled, Connection } from '@element-plus/icons-vue'
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
  kingbase:      { group: 'jdbc', port: 54321, dbLabel: '数据库名',   dbHint: 'test',       warn: true, note: '人大金仓，需手动放置 kingbase8-8.6.0.jar 到 lib/ 并加 system scope 依赖' },
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

const TAG_COLOR: Record<string, any> = {
  mysql: '', starrocks: 'success', doris: 'success', postgresql: 'primary', clickhouse: 'warning',
  oracle: 'danger', sqlserver: 'info', elasticsearch: 'warning', tdengine: 'success', hive: 'info',
  kafka: 'warning', redis: 'danger', minio: 'primary', hbase: 'success', mongodb: 'success'
}
const tagType = (t: string) => TAG_COLOR[t] || 'info'
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
    case 'postgresql': case 'greenplum': case 'opengauss':
      return `jdbc:postgresql://${host}:${port}/${d}`
    case 'clickhouse': return `jdbc:ch://${host}:${port}/${d}`
    case 'sqlserver': return `jdbc:sqlserver://${host}:${port};databaseName=${d};encrypt=false`
    case 'oracle': return `jdbc:oracle:thin:@${host}:${port}/${d}`
    case 'tdengine': return `jdbc:TAOS-RS://${host}:${port}/${d}`
    case 'hive': return `jdbc:hive2://${host}:${port}/${d}`
    default: return ''
  }
}

// 列表过滤
const filtered = computed(() => rows.value.filter(r =>
  (!kw.value || (r.name || '').toLowerCase().includes(kw.value.toLowerCase())) &&
  (!kwType.value || r.type === kwType.value)))

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

const tableDlg = ref(false); const tableLoading = ref(false); const tables = ref<any[]>([])
const cols = ref<any[]>([]); const colsTableName = ref(''); const current = ref<DataSourceRow | null>(null); const esMode = ref(false)

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

async function openTables(row: DataSourceRow) {
  current.value = row
  esMode.value = row.type === 'elasticsearch'
  tableDlg.value = true
  cols.value = []
  tableLoading.value = true
  try {
    const res: any = await api.daSourceTables(row.id)
    tables.value = esMode.value ? (res?.indices || []) : (Array.isArray(res) ? res : [])
  } catch (e: any) { tables.value = []; ElMessage.error(errMsg(e, '该数据源驱动可能未就绪或无法列源表')) }
  finally { tableLoading.value = false }
}
async function openCols(t: any) {
  if (!current.value) return
  colsTableName.value = t.name
  try { cols.value = await api.daSourceColumns(current.value.id, t.name, t.schema_name) } catch (e: any) { cols.value = []; ElMessage.error(errMsg(e)) }
}

onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.ct-left { display: inline-flex; align-items: center; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.hint b { color: var(--tech-primary); }
</style>
