<template>
  <div>
    <div class="dl-card">
      <div class="card-title">
        <span class="ct-left"><el-icon class="title-icon"><Download /></el-icon>离线数据接入</span>
        <div class="head-right">
          <span class="count-badge">共 <b>{{ filtered.length }}</b> 个任务</span>
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新建任务</el-button>
        </div>
      </div>

      <div class="dl-toolbar">
        <el-input v-model="kw" placeholder="任务名称" size="small" clearable style="width:180px" />
        <el-select v-model="kwStrategy" placeholder="策略" size="small" clearable style="width:120px">
          <el-option label="全量" value="FULL" />
          <el-option label="增量" value="INCREMENTAL" />
        </el-select>
        <el-select v-model="kwStatus" placeholder="状态" size="small" clearable style="width:120px">
          <el-option label="上线" value="ENABLED" />
          <el-option label="下线" value="DISABLED" />
        </el-select>
        <div class="toolbar-actions"><el-button size="small" @click="resetQuery">重置</el-button></div>
      </div>

      <el-table :data="paged" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="130" />
        <el-table-column prop="name" label="任务名" min-width="130" />
        <el-table-column label="源 → 目标" min-width="300">
          <template #default="{ row }">
            <span class="muted">{{ dsMap[row.source_ds_id] || ('ds#' + row.source_ds_id) }}/{{ row.source_table }}</span>
            <b style="margin:0 4px">→</b>
            <span>{{ row.target_ds_id ? (dsMap[row.target_ds_id] || ('ds#' + row.target_ds_id)) + '/' : '(主库)/' }}{{ row.target_db }}.{{ row.target_table }}</span>
          </template>
        </el-table-column>
        <el-table-column label="策略" width="80">
          <template #default="{ row }"><el-tag size="small" :type="row.strategy === 'INCREMENTAL' ? 'warning' : ''">{{ row.strategy === 'INCREMENTAL' ? '增量' : '全量' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ row.status === 'ENABLED' ? '上线' : '下线' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="last_sync_value" label="水位" width="140" />
        <el-table-column label="操作" width="400" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button size="small" link type="success" :loading="runningId === row.id" @click="run(row)">执行</el-button>
              <el-button size="small" link type="primary" @click="previewSource(row)">预览源</el-button>
              <el-button size="small" link type="primary" @click="runs(row)">历史</el-button>
              <el-button size="small" link type="primary" @click="copy(row)">复制</el-button>
              <el-button size="small" link type="primary" @click="viewJson(row)">JSON</el-button>
              <el-button v-if="row.status !== 'ENABLED'" size="small" link type="success" @click="online(row)">上线</el-button>
              <el-button v-else size="small" link type="warning" @click="offline(row)">下线</el-button>
              <el-button size="small" link type="primary" :disabled="row.status === 'ENABLED'" @click="open(row)">编辑</el-button>
              <el-button size="small" link type="danger" :disabled="row.status === 'ENABLED'" @click="del(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="filtered.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper"
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
      <div class="hint"><el-icon><InfoFilled /></el-icon> 目标可选任意关系型数据源；全量=truncate+insert；增量仅 StarRocks/Doris 目标（主键模型去重）；<b>上线状态编辑/删除置灰</b>。</div>
    </div>

    <!-- 新建/编辑 -->
    <el-dialog v-model="dlg" :title="form.id ? '编辑任务' : '新建任务'" width="580px">
      <el-form :model="form" label-width="92px" size="default">
        <el-form-item label="任务名"><el-input v-model="form.name" /></el-form-item>
        <el-divider content-position="left">源端配置</el-divider>
        <el-form-item label="源数据源">
          <el-select v-model="form.source_ds_id" style="width:100%" @change="onDs">
            <el-option v-for="d in dsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="源数据库">
          <el-select v-model="srcSchema" filterable :loading="tablesLoading" placeholder="选择数据库（schema）" @change="onSchema" style="width:100%">
            <el-option v-for="s in schemaOptions" :key="s || '__default__'" :label="s || '（默认）'" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="源表">
          <el-select v-model="form.source_table" filterable :loading="tablesLoading" placeholder="选择源表" no-data-text="请先选择数据库" style="width:100%">
            <el-option v-for="t in tableOptions" :key="qualifiedName(t)" :label="t.comment ? `${t.name}（${t.comment}）` : t.name" :value="qualifiedName(t)" />
          </el-select>
        </el-form-item>
        <el-form-item label="策略">
          <el-radio-group v-model="form.strategy">
            <el-radio value="FULL">全量</el-radio>
            <el-radio value="INCREMENTAL">增量</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="incBlocked" label=" ">
          <el-alert type="warning" :closable="false" show-icon title="增量仅支持 StarRocks/Doris 目标（依赖主键模型去重）；该目标请选全量" />
        </el-form-item>
        <el-form-item v-if="form.strategy === 'INCREMENTAL'" label="增量列"><el-input v-model="form.inc_column" placeholder="时间或自增列" /></el-form-item>
        <el-form-item v-if="form.strategy === 'INCREMENTAL'" label="业务唯一键"><el-input v-model="form.biz_key" placeholder="去重主键（留空取首列）" /></el-form-item>
        <el-form-item label="数据过滤"><el-input v-model="form.where_clause" type="textarea" :rows="2" placeholder="如 status=1 AND create_time>'2026-01-01'（不加 WHERE 关键字；增量时与水位 AND 组合）" /></el-form-item>
        <el-divider content-position="left">目标端配置</el-divider>
        <el-form-item label="目标数据源">
          <el-select v-model="form.target_ds_id" filterable placeholder="选择目标数据源" @change="onTgtDs" style="width:100%">
            <el-option v-for="d in targetDsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标库">
          <el-select v-model="tgtSchema" filterable allow-create default-first-option :loading="tgtLoading" placeholder="选择目标库，或输入新库名" @change="onTgtSchema" style="width:100%">
            <el-option v-for="s in tgtSchemaOptions" :key="s || '__tgt_default__'" :label="s || '（默认）'" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标表">
          <el-select v-model="form.target_table" filterable allow-create default-first-option :loading="tgtLoading" placeholder="留空则自动用源表名建表，或选择/输入表名" style="width:100%">
            <el-option v-for="t in tgtTableOptions" :key="t.name" :label="t.comment ? `${t.name}（${t.comment}）` : t.name" :value="t.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="DISABLED">下线（可编辑）</el-radio>
            <el-radio value="ENABLED">上线（锁定）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="incBlocked" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 源预览 -->
    <el-dialog v-model="previewDlg" :title="`源预览 - ${current?.name || ''}`" width="800px">
      <el-table :data="previewRows" size="small" border max-height="440" v-loading="previewLoading">
        <el-table-column v-for="c in previewCols" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <!-- 执行历史 + 清除日志 -->
    <el-dialog v-model="runsDlg" :title="`执行历史 - ${current?.name || ''}`" width="840px">
      <div class="dl-toolbar" style="padding:8px 12px;margin-bottom:8px">
        <span class="muted">清洗规则：</span>
        <el-select v-model="clearRule" size="small" style="width:160px">
          <el-option label="清除全部日志" value="all" />
          <el-option label="仅清除失败日志" value="failed" />
          <el-option label="清除 7 天前日志" value="before7d" />
        </el-select>
        <el-button size="small" type="danger" plain :loading="clearing" @click="clearRuns">清除日志</el-button>
        <div class="toolbar-actions"><span class="muted">共 {{ runRows.length }} 条</span></div>
      </div>
      <el-table :data="runRows" size="small" border max-height="380">
        <el-table-column prop="start_time" label="开始" width="155" />
        <el-table-column prop="end_time" label="结束" width="155" />
        <el-table-column prop="triggered_by" label="触发人" width="90" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="读/写" width="110">
          <template #default="{ row }">{{ row.rows_read }} / {{ row.rows_written }}</template>
        </el-table-column>
        <el-table-column prop="error_msg" label="错误" min-width="180" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <!-- JSON 查看 -->
    <el-dialog v-model="jsonDlg" title="任务配置 JSON" width="640px">
      <pre class="json-box">{{ jsonText }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled, Download } from '@element-plus/icons-vue'
import { api, errMsg, type DataSourceRow, type OfflineJobRow } from '@/api'

const jobs = ref<OfflineJobRow[]>([])
const dsList = ref<DataSourceRow[]>([])
const loading = ref(false)
const dlg = ref(false); const saving = ref(false); const runningId = ref<number | null>(null)
const form = reactive<any>({ id: null, name: '', source_ds_id: null, source_table: '', target_ds_id: null, target_db: 'ods', target_table: '', strategy: 'FULL', inc_column: '', biz_key: '', where_clause: '', status: 'DISABLED' })

const dsMap = computed<Record<number, string>>(() => {
  const m: Record<number, string> = {}
  dsList.value.forEach(d => { m[d.id] = `${d.name}(${d.type})` })
  return m
})

// 检索 + 分页（客户端）
const kw = ref(''); const kwStrategy = ref(''); const kwStatus = ref('')
const page = reactive({ page: 1, size: 10 })
const filtered = computed(() => jobs.value.filter(j =>
  (!kw.value || (j.name || '').toLowerCase().includes(kw.value.toLowerCase())) &&
  (!kwStrategy.value || j.strategy === kwStrategy.value) &&
  (!kwStatus.value || j.status === kwStatus.value)))
const paged = computed(() => filtered.value.slice((page.page - 1) * page.size, page.page * page.size))
function resetQuery() { kw.value = ''; kwStrategy.value = ''; kwStatus.value = ''; page.page = 1 }
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }

const previewDlg = ref(false); const previewLoading = ref(false); const previewCols = ref<string[]>([]); const previewRows = ref<any[]>([])
const runsDlg = ref(false); const runRows = ref<any[]>([]); const clearing = ref(false); const clearRule = ref('all')
const jsonDlg = ref(false); const jsonText = ref('')
const current = ref<OfflineJobRow | null>(null)

async function load() {
  loading.value = true
  try {
    const [j, d] = await Promise.all([api.daOfflineJobs(), api.daSources()])
    jobs.value = j; dsList.value = d
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}

// 源表按库选择：一次拉取数据源下全部表，前端按 schema 分组
const srcSchema = ref('')
const srcTables = ref<any[]>([])
const tablesLoading = ref(false)
const schemaOptions = computed(() => {
  const set = new Set<string>()
  srcTables.value.forEach(t => set.add((t.schema_name || '').toString()))
  return Array.from(set).sort()
})
const tableOptions = computed(() => srcTables.value.filter(t => (t.schema_name || '') === srcSchema.value))
function qualifiedName(t: any) {
  const sch = t.schema_name ? String(t.schema_name) : ''
  return sch ? `${sch}.${t.name}` : String(t.name)
}

async function loadTables(dsId: number | null, preferSchema = '') {
  srcTables.value = []
  srcSchema.value = ''
  if (!dsId) return
  tablesLoading.value = true
  try {
    const r: any = await api.daSourceTables(dsId)
    if (Array.isArray(r)) srcTables.value = r
    else if (r && Array.isArray(r.indices)) srcTables.value = r.indices.map((n: string) => ({ name: n, schema_name: '', comment: '' }))
    else srcTables.value = []
    if (schemaOptions.value.includes(preferSchema)) srcSchema.value = preferSchema
    else if (schemaOptions.value.length === 1) srcSchema.value = schemaOptions.value[0]
  } catch (e) {
    srcTables.value = []
    ElMessage.warning('源库表拉取失败：' + errMsg(e, '请检查数据源连通性/驱动'))
  } finally {
    tablesLoading.value = false
  }
}

function onDs() {
  form.source_table = ''
  loadTables(form.source_ds_id)
}
function onSchema() {
  form.source_table = ''
}

// 目标端：同样按 数据源 → 库 → 表 选择。目标可选任意关系型数据源；增量仅 starrocks/doris
const RELATIONAL_TARGET_TYPES = new Set(['mysql', 'starrocks', 'doris', 'postgresql', 'greenplum', 'opengauss', 'clickhouse', 'sqlserver', 'oracle'])
const tgtSchema = ref('')
const tgtTables = ref<any[]>([])
const tgtLoading = ref(false)
const targetDsList = computed(() => dsList.value.filter((d: any) => RELATIONAL_TARGET_TYPES.has(d.type)))
const tgtDsDbName = computed(() => dsList.value.find((d: any) => d.id === form.target_ds_id)?.db_name || '')
const tgtSchemaOptions = computed(() => {
  const set = new Set<string>()
  tgtTables.value.forEach(t => set.add((t.schema_name || '').toString()))
  if (tgtDsDbName.value) set.add(tgtDsDbName.value)   // 数据源配置的库即使为空也展示
  return Array.from(set).sort()
})
const tgtTableOptions = computed(() => tgtTables.value.filter(t => (t.schema_name || '') === tgtSchema.value))
// 增量校验：增量仅支持 StarRocks/Doris 目标（依赖主键模型去重）
const tgtType = computed(() => dsList.value.find((d: any) => d.id === form.target_ds_id)?.type || '')
const incBlocked = computed(() => form.strategy === 'INCREMENTAL' && !!tgtType.value && tgtType.value !== 'starrocks' && tgtType.value !== 'doris')

async function loadTargetTables(dsId: number | null, preferSchema = '') {
  tgtTables.value = []
  tgtSchema.value = ''
  if (!dsId) return
  tgtLoading.value = true
  try {
    const r: any = await api.daSourceTables(dsId)
    if (Array.isArray(r)) tgtTables.value = r
    else if (r && Array.isArray(r.indices)) tgtTables.value = r.indices.map((n: string) => ({ name: n, schema_name: '', comment: '' }))
    else tgtTables.value = []
    if (preferSchema) tgtSchema.value = preferSchema
    else if (tgtSchemaOptions.value.length === 1) tgtSchema.value = tgtSchemaOptions.value[0]
  } catch (e) {
    tgtTables.value = []
    ElMessage.warning('目标库表拉取失败：' + errMsg(e, '请检查目标数据源连通性'))
  } finally {
    tgtLoading.value = false
  }
}
function onTgtDs() {
  form.target_db = ''
  form.target_table = ''
  loadTargetTables(form.target_ds_id)
}
function onTgtSchema() {
  form.target_table = ''
}

function open(row?: OfflineJobRow) {
  Object.assign(form, { id: null, name: '', source_ds_id: dsList.value[0]?.id || null, source_table: '', target_ds_id: targetDsList.value[0]?.id || null, target_db: 'ods', target_table: '', strategy: 'FULL', inc_column: '', biz_key: '', where_clause: '', status: 'DISABLED' })
  if (row) {
    Object.assign(form, { id: row.id, name: row.name, source_ds_id: row.source_ds_id, source_table: row.source_table, target_ds_id: row.target_ds_id ?? null, target_db: row.target_db, target_table: row.target_table, strategy: row.strategy, inc_column: row.inc_column || '', biz_key: row.biz_key || '', where_clause: (row as any).where_clause || '', status: row.status })
    // 回显：从 source_table（可能是 schema.table）拆出 schema 并加载该数据源的表
    const dot = (row.source_table || '').lastIndexOf('.')
    loadTables(row.source_ds_id, dot > 0 ? row.source_table.substring(0, dot) : '')
    // 回显目标端：加载目标数据源的表并定位到目标库
    loadTargetTables(form.target_ds_id, row.target_db || '')
  } else {
    loadTables(form.source_ds_id)
    loadTargetTables(form.target_ds_id, tgtDsDbName.value)
  }
  dlg.value = true
}

async function save() {
  if (!form.name || !form.source_ds_id || !form.source_table) return ElMessage.warning('请补全：名称/源/源表')
  // 目标表留空 → 默认用源表名（去 schema 前缀），保存后后端自动建表
  if (!form.target_table) {
    const src = form.source_table || ''
    const dot = src.lastIndexOf('.')
    form.target_table = dot > 0 ? src.substring(dot + 1) : src
  }
  if (incBlocked.value) return ElMessage.warning('该目标仅支持全量，请改策略为全量或更换为 StarRocks/Doris 目标')
  form.target_db = tgtSchema.value
  saving.value = true
  try { await api.daSaveOfflineJob({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: OfflineJobRow) {
  try { await ElMessageBox.confirm(`确定删除任务 ${row.name}？将同时删除其历史。`, '提示', { type: 'warning' }) } catch { return }
  try { await api.daDeleteOfflineJob(row.id); ElMessage.success('已删除'); await load() } catch (e) { ElMessage.error(errMsg(e)) }
}

async function copy(row: OfflineJobRow) {
  try { const r: any = await api.daOfflineCopy(row.id); ElMessage.success(`已复制为 ${r.name}`); await load() }
  catch (e) { ElMessage.error(errMsg(e)) }
}
async function online(row: OfflineJobRow) { try { await api.daOfflineOnline(row.id); ElMessage.success('已上线（锁定）'); await load() } catch (e) { ElMessage.error(errMsg(e)) } }
async function offline(row: OfflineJobRow) { try { await api.daOfflineOffline(row.id); ElMessage.success('已下线'); await load() } catch (e) { ElMessage.error(errMsg(e)) } }

async function run(row: OfflineJobRow) {
  runningId.value = row.id
  try {
    const r: any = await api.daOfflineRun(row.id)
    if (r.success) ElMessage.success(`执行成功：读 ${r.rowsRead} / 写 ${r.rowsWritten}`)
    else ElMessageBox.alert(r.msg || '执行失败', '失败', { type: 'error' })
    await load()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { runningId.value = null }
}

async function previewSource(row: OfflineJobRow) {
  current.value = row; previewDlg.value = true; previewLoading.value = true
  try {
    const r: any = await api.daOfflinePreview({ source_ds_id: row.source_ds_id, source_table: row.source_table, limit: 50 })
    previewCols.value = r.columns || []; previewRows.value = r.rows || []
  } catch (e: any) { previewCols.value = []; previewRows.value = []; ElMessage.error(errMsg(e, '预览失败：检查源表名/驱动')) }
  finally { previewLoading.value = false }
}

async function runs(row: OfflineJobRow) {
  current.value = row; runsDlg.value = true
  try { runRows.value = await api.daOfflineRuns(row.id) } catch (e: any) { runRows.value = []; ElMessage.error(errMsg(e)) }
}
async function clearRuns() {
  if (!current.value) return
  try { await ElMessageBox.confirm(`按规则清除该任务日志？`, '提示', { type: 'warning' }) } catch { return }
  clearing.value = true
  try { const r: any = await api.daOfflineClearRuns(current.value.id, clearRule.value); ElMessage.success(`已清除 ${r.deleted} 条`); runRows.value = await api.daOfflineRuns(current.value.id) }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { clearing.value = false }
}

function viewJson(row: OfflineJobRow) {
  jsonText.value = JSON.stringify(row, null, 2); jsonDlg.value = true
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
.json-box { background: var(--el-fill-color-light); padding: 12px; border-radius: 6px; max-height: 460px; overflow: auto; font-size: 12px; white-space: pre-wrap; color: var(--tech-text); }
</style>
