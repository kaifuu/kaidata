<template>
  <div>
    <div class="dl-card">
      <div class="card-title">
        <span>数据源管理</span>
        <div style="display:flex;align-items:center;gap:10px">
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新增数据源</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.type)">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column label="地址" min-width="180">
          <template #default="{ row }">{{ row.host }}:{{ row.port }}<span v-if="row.db_name"> / {{ row.db_name }}</span></template>
        </el-table-column>
        <el-table-column prop="username" label="账号" width="110" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'NORMAL' ? 'success' : 'warning'">{{ row.status === 'NORMAL' ? '正常' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="success" :loading="testingId === row.id" @click="test(row)">测试</el-button>
            <el-button size="small" link type="primary" @click="openTables(row)">源表</el-button>
            <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
            <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint">
        <el-icon><InfoFilled /></el-icon>
        支持 13 类数据源；<b>国产库（达梦/人大金仓/南大通用）</b>驱动需手动放 jar，未放前可登记但测试会提示。密码 AES-GCM 加密存储。
      </div>
    </div>

    <!-- 新增/编辑（按类型动态切换参数） -->
    <el-dialog v-model="dlg" :title="form.id ? '编辑数据源' : '新增数据源'" width="540px">
      <el-form :model="form" label-width="86px" size="default">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width:100%" @change="onType">
            <el-option v-for="t in types" :key="t.code" :label="t.code + (t.driverAvailable ? '' : '（需放jar）')" :value="t.code" />
          </el-select>
        </el-form-item>

        <!-- 类型说明 -->
        <el-form-item v-if="spec.note" label=" ">
          <el-alert :title="spec.note" :type="spec.warn ? 'warning' : 'info'" :closable="false" show-icon style="width:100%" />
        </el-form-item>

        <el-form-item label="主机"><el-input v-model="form.host" :placeholder="spec.group === 'es' ? '如 127.0.0.1' : '如 127.0.0.1'" /></el-form-item>
        <el-form-item :label="spec.group === 'es' ? '端口' : '端口'">
          <el-input-number v-model="form.port" :min="0" :max="65535" controls-position="right" style="width:100%" />
        </el-form-item>

        <!-- 库名：JDBC 类显示（label 随类型变化）；ES 无库概念 -->
        <el-form-item v-if="spec.group === 'jdbc'" :label="spec.dbLabel">
          <el-input v-model="form.db_name" :placeholder="spec.dbHint" />
        </el-form-item>
        <el-form-item v-else label=" ">
          <span class="muted">Elasticsearch 按索引(Index)访问，无需指定库；列源表时枚举索引。</span>
        </el-form-item>

        <el-form-item label="账号"><el-input v-model="form.username" :placeholder="spec.group === 'es' ? 'Basic 认证用户名（可留空）' : '用户名'" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空则不修改' : (spec.group === 'es' ? 'Basic 认证密码（可留空）' : '请输入密码')" />
        </el-form-item>
        <el-form-item label="扩展参数"><el-input v-model="form.props" type="textarea" :rows="2" placeholder='JSON，如 {"ssl":"false","charset":"utf8"}；高级选项可留空' /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="NORMAL">正常</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 源表浏览 -->
    <el-dialog v-model="tableDlg" :title="`源表浏览 - ${current?.name || ''}`" width="720px">
      <!-- JDBC：表列表 -->
      <el-table v-if="esMode === false" :data="tables" size="small" border v-loading="tableLoading" max-height="420">
        <el-table-column prop="name" label="表名" min-width="200" />
        <el-table-column prop="schema_name" label="Schema" width="140" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }"><el-button link type="primary" size="small" @click="openCols(row)">查看字段</el-button></template>
        </el-table-column>
      </el-table>
      <!-- ES：索引列表 -->
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
import { Plus, InfoFilled } from '@element-plus/icons-vue'
import { api, errMsg, type DataSourceRow, type DataSourceType } from '@/api'

// 各数据源类型的参数规格：默认端口 / 库名语义 / 分组（jdbc=关系型库, es=搜索引擎）/ 提示
const DS_SPECS: Record<string, any> = {
  mysql:         { group: 'jdbc', port: 3306,  dbLabel: '数据库名',   dbHint: 'database',   warn: false },
  starrocks:     { group: 'jdbc', port: 9030,  dbLabel: '数据库名',   dbHint: 'ods / dwd',  warn: false },
  doris:         { group: 'jdbc', port: 9030,  dbLabel: '数据库名',   dbHint: 'internal',   warn: false },
  postgresql:    { group: 'jdbc', port: 5432,  dbLabel: '数据库名',   dbHint: 'postgres',   warn: false },
  greenplum:     { group: 'jdbc', port: 5432,  dbLabel: '数据库名',   dbHint: 'postgres',   note: '基于 PostgreSQL 协议（驱动复用 pg）' },
  opengauss:     { group: 'jdbc', port: 5432,  dbLabel: '数据库名',   dbHint: 'postgres',   note: '华为高斯，基于 PostgreSQL 协议（驱动复用 pg）' },
  clickhouse:    { group: 'jdbc', port: 8123,  dbLabel: '数据库',     dbHint: 'default',    note: '经 HTTP 接口接入（默认端口 8123）' },
  sqlserver:     { group: 'jdbc', port: 1433,  dbLabel: '数据库名',   dbHint: 'master',     warn: false },
  oracle:        { group: 'jdbc', port: 1521,  dbLabel: '服务名/SID', dbHint: 'ORCL / ORCLPDB1', note: '服务名形式 host:port/服务名' },
  tdengine:      { group: 'jdbc', port: 6041,  dbLabel: '库名',       dbHint: 'test',       note: 'TDengine 经 REST 接入（默认端口 6041），支持超表/子表', warn: true },
  hive:          { group: 'jdbc', port: 10000, dbLabel: '数据库',     dbHint: 'default',    note: '需用 Maven profile with-hive 构建后才可连通', warn: true },
  kingbase:      { group: 'jdbc', port: 54321, dbLabel: '数据库名',   dbHint: 'test',       note: '人大金仓，需手动放置 kingbase8-8.6.0.jar 到 lib/ 并加 system scope 依赖', warn: true },
  dameng:        { group: 'jdbc', port: 5236,  dbLabel: '库名',       dbHint: 'SYSDBA',     note: '达梦，需手动放置 DmJdbcDriver18.jar 到 lib/ 并加 system scope 依赖', warn: true },
  gbase:         { group: 'jdbc', port: 5258,  dbLabel: '库名',       dbHint: 'test',       note: '南大通用，需手动放置 gbase-connector-java.jar 到 lib/ 并加 system scope 依赖', warn: true },
  elasticsearch: { group: 'es',   port: 9200,  dbLabel: '',           dbHint: '',           note: 'REST 接口，按索引(Index)访问，无数据库概念；账号密码为 HTTP Basic 认证（可留空）' }
}
const TAG_COLOR: Record<string, any> = {
  mysql: '', starrocks: 'success', doris: 'success', postgresql: 'primary', clickhouse: 'warning',
  oracle: 'danger', sqlserver: 'info', elasticsearch: 'warning', tdengine: 'success', hive: 'info'
}
const tagType = (t: string) => TAG_COLOR[t] || 'info'

const rows = ref<DataSourceRow[]>([])
const types = ref<DataSourceType[]>([])
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const testingId = ref<number | null>(null)
const form = reactive<any>({ id: null, name: '', type: 'mysql', host: '127.0.0.1', port: 3306, db_name: '', username: '', password: '', props: '', status: 'NORMAL' })

// 当前类型规格（驱动表单字段 label/默认/显隐）
const spec = computed<any>(() => DS_SPECS[form.type] || { group: 'jdbc', port: 3306, dbLabel: '数据库名', dbHint: '', warn: false })

function onType(t: string) {
  const s = DS_SPECS[t]
  if (s) {
    form.port = s.port
    if (s.group === 'es') form.db_name = ''   // ES 无库概念
  }
}

const tableDlg = ref(false)
const tableLoading = ref(false)
const tables = ref<any[]>([])
const cols = ref<any[]>([])
const colsTableName = ref('')
const current = ref<DataSourceRow | null>(null)
const esMode = ref(false)   // 当前浏览的数据源是否为 ES

async function load() {
  loading.value = true
  try {
    const [r, t] = await Promise.all([api.daSources(), api.daSourceTypes()])
    rows.value = r
    types.value = t
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}

function open(row?: DataSourceRow) {
  Object.assign(form, { id: null, name: '', type: 'mysql', host: '127.0.0.1', port: 3306, db_name: '', username: '', password: '', props: '', status: 'NORMAL' })
  if (row) Object.assign(form, { id: row.id, name: row.name, type: row.type, host: row.host, port: row.port, db_name: row.db_name, username: row.username, props: row.props || '', status: row.status, password: '' })
  dlg.value = true
}

async function save() {
  if (!form.name || !form.type) return ElMessage.warning('请填写名称与类型')
  saving.value = true
  try { await api.daSaveSource({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: DataSourceRow) {
  await ElMessageBox.confirm(`确定删除数据源 ${row.name}？`, '提示', { type: 'warning' })
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

async function openTables(row: DataSourceRow) {
  current.value = row
  esMode.value = row.type === 'elasticsearch'
  tableDlg.value = true
  cols.value = []
  tableLoading.value = true
  try {
    const res: any = await api.daSourceTables(row.id)
    if (esMode.value) {
      // ES 返回 {type, indices:[...]}
      tables.value = Array.isArray(res) ? res : (res?.indices || [])
    } else {
      tables.value = Array.isArray(res) ? res : []
    }
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
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.hint b { color: var(--tech-primary); }
</style>
