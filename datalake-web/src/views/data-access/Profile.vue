<template>
  <div>
    <div class="dl-card">
      <div class="card-title">
        <span>数据探查任务</span>
        <div style="display:flex;align-items:center;gap:10px">
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新建探查任务</el-button>
        </div>
      </div>
      <div style="margin-bottom:10px;display:flex;gap:8px;align-items:center">
        <el-input v-model="kw" placeholder="任务名称/状态" size="small" style="width:240px" clearable />
        <el-button size="small" @click="filter">查询</el-button>
      </div>
      <el-table :data="filtered" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="name" label="任务名" min-width="130" />
        <el-table-column label="源" width="100"><template #default="{ row }">ds{{ row.source_ds_id }}</template></el-table-column>
        <el-table-column prop="target_db" label="层级" width="80" />
        <el-table-column prop="cron" label="周期(秒)" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'ONLINE' ? 'success' : 'info'">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="success" :loading="runningId === row.id" @click="run(row)">执行</el-button>
            <el-button v-if="row.status !== 'ONLINE'" size="small" link type="primary" @click="online(row)">上线</el-button>
            <el-button v-else size="small" link type="warning" @click="offline(row)">下线</el-button>
            <el-button size="small" link type="primary" @click="openRuns(row)">日志</el-button>
            <el-button size="small" link type="primary" @click="openRecords(row)">记录</el-button>
            <el-button size="small" link type="primary" :disabled="row.status === 'ONLINE'" @click="open(row)">编辑</el-button>
            <el-button size="small" link type="danger" :disabled="row.status === 'ONLINE'" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint"><el-icon><InfoFilled /></el-icon> 探查感知表结构变化形成版本；首次探查可自动建模到所选层级；勾选字段统计唯一值/空值/数值分布。</div>
    </div>

    <!-- 新建/编辑 -->
    <el-dialog v-model="dlg" :title="form.id ? '编辑探查任务' : '新建探查任务'" width="860px" top="6vh">
      <div class="edit-grid">
        <!-- 左：数据源 + 表勾选 -->
        <div class="pane">
          <div class="sub-t">探查对象</div>
          <el-select v-model="form.source_ds_id" placeholder="选择源数据源" size="small" style="width:100%;margin-bottom:8px" @change="loadTables">
            <el-option v-for="d in dsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
          <el-input v-if="sourceTables.length" v-model="tableKw" size="small" placeholder="表名模糊检索" clearable style="margin-bottom:6px" />
          <div class="table-list">
            <div v-for="t in filteredTables" :key="t.full" class="table-row" :class="{ on: selectedTables.includes(t.full) }">
              <el-checkbox :model-value="selectedTables.includes(t.full)" @change="(v:any) => toggleTable(t.full, v)">
                <el-icon v-if="t.is_view"><Document /></el-icon> {{ t.name }}
              </el-checkbox>
              <el-button v-if="selectedTables.includes(t.full)" link size="small" type="primary" @click="openColCfg(t)">配置字段</el-button>
            </div>
            <div v-if="sourceTables.length && !filteredTables.length" class="muted">无匹配表</div>
            <div v-if="!sourceTables.length" class="muted">选择数据源后加载表</div>
          </div>
        </div>
        <!-- 右：任务表单 -->
        <div class="pane">
          <div class="sub-t">任务参数</div>
          <el-form :model="form" label-width="92px" size="small">
            <el-form-item label="任务名称"><el-input v-model="form.name" /></el-form-item>
            <el-form-item label="所属层级">
              <el-select v-model="form.target_db" style="width:100%">
                <el-option v-for="l in ['ods','dwd','dws','dim','ads']" :key="l" :label="l.toUpperCase() + ' 层'" :value="l" />
              </el-select>
            </el-form-item>
            <el-form-item label="首次建表"><el-switch v-model="form.first_create_table" /></el-form-item>
            <el-form-item label="结构告警"><el-switch v-model="form.alert_enabled" /></el-form-item>
            <el-form-item label="数据元(JSON)"><el-input v-model="form.extra_columns" type="textarea" :rows="2" placeholder='[{"name":"etl_time","type":"DATETIME"}]（首次建表时追加列）' /></el-form-item>
            <el-form-item label="周期(秒)"><el-input v-model="form.cron" placeholder="如 60（上线后按此间隔周期执行）" /></el-form-item>
          </el-form>
        </div>
      </div>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 字段配置 -->
    <el-dialog v-model="colDlg" :title="`字段探查配置 - ${curTable}`" width="680px">
      <el-table :data="curCols" size="small" border v-loading="colLoading" max-height="380">
        <el-table-column prop="name" label="字段" min-width="140" />
        <el-table-column prop="type" label="类型" width="120" />
        <el-table-column label="唯一值" width="70"><template #default="{ row }"><el-checkbox v-model="colOpts[row.name].unique" /></template></el-table-column>
        <el-table-column label="空值" width="60"><template #default="{ row }"><el-checkbox v-model="colOpts[row.name].null" /></template></el-table-column>
        <el-table-column label="取值分布" width="90">
          <template #default="{ row }">
            <el-checkbox v-model="colOpts[row.name].dist" :disabled="!isNumeric(row.type)" />
            <span v-if="!isNumeric(row.type)" class="muted">数值列</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer><el-button @click="colDlg = false">取消</el-button><el-button type="primary" @click="saveColCfg">确定</el-button></template>
    </el-dialog>

    <!-- 日志 -->
    <el-dialog v-model="runsDlg" :title="`探查日志 - ${current?.name || ''}`" width="820px">
      <el-table :data="runRows" size="small" border max-height="300">
        <el-table-column prop="start_time" label="开始" width="155" />
        <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="变化/总数" width="90"><template #default="{ row }">{{ row.tables_changed }} / {{ row.tables_total }}</template></el-table-column>
        <el-table-column prop="error_msg" label="错误" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link size="small" type="primary" @click="openLog(row)">详情</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

    <!-- 日志详情 -->
    <el-dialog v-model="logDlg" title="执行日志详情" width="720px">
      <div style="margin-bottom:8px"><el-button size="small" @click="copyLog">一键复制日志</el-button></div>
      <pre class="log-box">{{ curLog }}</pre>
    </el-dialog>

    <!-- 记录 + 版本对比 -->
    <el-dialog v-model="recDlg" :title="`探查记录 - ${current?.name || ''}`" width="900px">
      <div style="margin-bottom:8px;display:flex;gap:8px;align-items:center">
        <el-select v-model="recTable" placeholder="选择表" size="small" style="width:240px" @change="loadCompare">
          <el-option v-for="t in recTables" :key="t" :label="t" :value="t" />
        </el-select>
        <span class="muted">勾选两个版本对比字段变化：</span>
        <el-select v-model="cmpV1" size="small" style="width:120px" @change="loadCompare"><el-option v-for="v in recVersions" :key="v" :label="'v'+v" :value="v" /></el-select>
        <el-select v-model="cmpV2" size="small" style="width:120px" @change="loadCompare"><el-option v-for="v in recVersions" :key="v" :label="'v'+v" :value="v" /></el-select>
      </div>
      <el-table v-if="cmpCols.length" :data="cmpCols" size="small" border max-height="380">
        <el-table-column prop="name" label="字段" min-width="150" />
        <el-table-column label="v1 类型" width="140"><template #default="{ row }"><span :class="{rm: row.r1}">{{ row.t1 }}</span></template></el-table-column>
        <el-table-column label="v2 类型" width="140"><template #default="{ row }"><span :class="{add: row.r2, chg: row.chg}">{{ row.t2 }}</span></template></el-table-column>
      </el-table>
      <div v-else class="muted" style="padding:12px 0">选择表 + 两个版本查看字段对比</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled, Document } from '@element-plus/icons-vue'
import { api, errMsg, type DataSourceRow, type ProfileJobRow } from '@/api'

const jobs = ref<ProfileJobRow[]>([])
const dsList = ref<DataSourceRow[]>([])
const loading = ref(false)
const kw = ref('')
const filtered = computed(() => {
  if (!kw.value) return jobs.value
  const k = kw.value.toLowerCase()
  return jobs.value.filter(j => j.name.toLowerCase().includes(k) || j.status.toLowerCase().includes(k))
})
const runningId = ref<number | null>(null)
const current = ref<ProfileJobRow | null>(null)

const dlg = ref(false)
const saving = ref(false)
const form = reactive<any>({ id: null, name: '', source_ds_id: null, target_db: 'dwd', first_create_table: true, alert_enabled: true, extra_columns: '', cron: '', status: 'OFFLINE' })

const sourceTables = ref<any[]>([])
const tableKw = ref('')
const selectedTables = ref<string[]>([])
const tableCfgs = reactive<Record<string, Record<string, any>>>({})
const filteredTables = computed(() => {
  if (!tableKw.value) return sourceTables.value
  const k = tableKw.value.toLowerCase()
  return sourceTables.value.filter((t:any) => t.name.toLowerCase().includes(k))
})

const colDlg = ref(false)
const colLoading = ref(false)
const curTable = ref('')
const curCols = ref<any[]>([])
const colOpts = reactive<Record<string, any>>({})

const runsDlg = ref(false)
const runRows = ref<any[]>([])
const logDlg = ref(false)
const curLog = ref('')

const recDlg = ref(false)
const recTables = ref<string[]>([])
const recTable = ref('')
const recVersions = ref<number[]>([])
const cmpV1 = ref<number | null>(null)
const cmpV2 = ref<number | null>(null)
const cmpCols = ref<any[]>([])

function isNumeric(t: string) {
  const s = (t || '').toLowerCase()
  return /int|long|bigint|double|decimal|float|numeric|real/.test(s)
}

async function load() {
  loading.value = true
  try {
    const [j, d] = await Promise.all([api.daProfileJobs(), api.daSources()])
    jobs.value = j; dsList.value = d
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}
function filter() {}

async function loadTables(dsId: number) {
  selectedTables.value = []
  sourceTables.value = []
  if (!dsId) return
  try {
    const list = await api.daProfileTables(dsId)
    sourceTables.value = (list || []).map((t:any) => ({ name: t.name, full: (t.schema_name ? t.schema_name + '.' : '') + t.name, is_view: false }))
  } catch (e:any) { ElMessage.error(errMsg(e, '加载源表失败')) }
}

function toggleTable(full: string, v: any) {
  if (v) { selectedTables.value.push(full); tableCfgs[full] = tableCfgs[full] || {} }
  else { selectedTables.value = selectedTables.value.filter(x => x !== full); delete tableCfgs[full] }
}

async function openColCfg(t: any) {
  curTable.value = t.full
  colDlg.value = true
  colLoading.value = true
  try {
    curCols.value = await api.daProfileColumns(form.source_ds_id, t.full)
    Object.keys(colOpts).forEach(k => delete colOpts[k])
    const exist = tableCfgs[t.full] || {}
    curCols.value.forEach((c:any) => {
      const e = exist[c.name] || {}
      colOpts[c.name] = { unique: !!e.unique, null: !!e.null, dist: !!e.dist }
    })
  } catch (e:any) { ElMessage.error(errMsg(e)) } finally { colLoading.value = false }
}
function saveColCfg() {
  const cfg: Record<string, any> = {}
  curCols.value.forEach((c:any) => {
    const o = colOpts[c.name]
    const opts: string[] = []
    if (o.unique) opts.push('unique')
    if (o.null) opts.push('null')
    if (o.dist && isNumeric(c.type)) opts.push('dist')
    if (opts.length) cfg[c.name] = opts
  })
  tableCfgs[curTable.value] = cfg
  colDlg.value = false
  ElMessage.success('字段配置已保存')
}

function open(row?: ProfileJobRow) {
  Object.assign(form, { id: null, name: '', source_ds_id: null, target_db: 'dwd', first_create_table: true, alert_enabled: true, extra_columns: '', cron: '', status: 'OFFLINE' })
  selectedTables.value = []
  Object.keys(tableCfgs).forEach(k => delete tableCfgs[k])
  sourceTables.value = []
  if (row) {
    Object.assign(form, { id: row.id, name: row.name, source_ds_id: row.source_ds_id, target_db: row.target_db, first_create_table: row.first_create_table, alert_enabled: row.alert_enabled, extra_columns: row.extra_columns || '', cron: row.cron || '', status: row.status })
    loadTables(row.source_ds_id).then(() => loadJobDetail(row.id))
  }
  dlg.value = true
}
async function loadJobDetail(id: number) {
  try {
    const d = await api.daProfileJobDetail(id)
    const arr: any[] = d.tables || []
    arr.forEach((t:any) => {
      const full = t.table_name
      selectedTables.value.push(full)
      try { tableCfgs[full] = JSON.parse(t.columns_config || '{}') } catch { tableCfgs[full] = {} }
    })
  } catch (e:any) { /* ignore */ }
}

async function save() {
  if (!form.name || !form.source_ds_id) return ElMessage.warning('请填名称与源数据源')
  if (!selectedTables.value.length) return ElMessage.warning('请至少勾选一张表')
  const tables = selectedTables.value.map(t => ({ table_name: t, is_view: false, columns_config: JSON.stringify(tableCfgs[t] || {}) }))
  saving.value = true
  try { await api.daSaveProfileJob({ ...form, tables }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: ProfileJobRow) {
  await ElMessageBox.confirm(`确定删除探查任务 ${row.name}？`, '提示', { type: 'warning' })
  try { await api.daDeleteProfileJob(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) }
}

async function run(row: ProfileJobRow) {
  runningId.value = row.id
  try { const r:any = await api.daProfileRun(row.id); ElMessage.success(`执行完成：变化 ${r.tablesChanged}/${r.tablesTotal}`); await load() }
  catch (e:any) { ElMessage.error(errMsg(e)) } finally { runningId.value = null }
}
async function online(row: ProfileJobRow) { try { await api.daProfileOnline(row.id); ElMessage.success('已上线'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function offline(row: ProfileJobRow) { try { await api.daProfileOffline(row.id); ElMessage.success('已下线'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }

async function openRuns(row: ProfileJobRow) {
  current.value = row; runsDlg.value = true
  try { runRows.value = await api.daProfileRuns(row.id) } catch { runRows.value = [] }
}
async function openLog(row: any) {
  try { const d = await api.daProfileRunDetail(row.id); curLog.value = d.log_text || '(无日志)'; logDlg.value = true }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function copyLog() {
  try { await navigator.clipboard.writeText(curLog.value); ElMessage.success('已复制') } catch { ElMessage.warning('复制失败，请手动选择') }
}

async function openRecords(row: ProfileJobRow) {
  current.value = row; recDlg.value = true; cmpCols.value = []; recTable.value = ''; cmpV1.value = null; cmpV2.value = null
  try {
    const list = await api.daProfileRecords(row.id)
    const map = new Map<string, number[]>()
    ;(list || []).forEach((s:any) => { const arr = map.get(s.table_name) || []; arr.push(s.version_n); map.set(s.table_name, arr) })
    recTables.value = [...map.keys()]
    if (recTables.value.length) { recTable.value = recTables.value[0]; recVersions.value = map.get(recTable.value) || []; await loadCompare() }
  } catch { recTables.value = [] }
}

async function loadCompare() {
  cmpCols.value = []
  if (!recTable.value || !cmpV1.value || !cmpV2.value || !current.value) return
  try {
    const r = await api.daProfileCompare(current.value.id, recTable.value, cmpV1.value, cmpV2.value)
    const m1 = parseCols(r.v1?.columns_json)
    const m2 = parseCols(r.v2?.columns_json)
    const names = new Set([...Object.keys(m1), ...Object.keys(m2)])
    const rows: any[] = []
    names.forEach(n => {
      const t1 = m1[n], t2 = m2[n]
      rows.push({ name: n, t1: t1 || '—', t2: t2 || '—', r1: !t1, r2: !t2, chg: !!(t1 && t2 && t1 !== t2) })
    })
    cmpCols.value = rows
  } catch (e:any) { ElMessage.error(errMsg(e)) }
}
function parseCols(json: any): Record<string, string> {
  const out: Record<string, string> = {}
  try { JSON.parse(json || '[]').forEach((c:any) => { out[c.name] = c.type }) } catch {}
  return out
}

onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.hint b { color: var(--tech-primary); }
.edit-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.pane { border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 12px; }
.sub-t { font-weight: 600; margin-bottom: 10px; color: var(--tech-text); }
.table-list { max-height: 320px; overflow-y: auto; }
.table-row { display: flex; align-items: center; justify-content: space-between; padding: 4px 6px; border-radius: 4px; }
.table-row.on { background: color-mix(in srgb, var(--tech-primary) 10%, transparent); }
.log-box { background: var(--el-fill-color-light); padding: 10px; border-radius: 6px; max-height: 420px; overflow: auto; font-size: 12px; white-space: pre-wrap; color: var(--tech-text); }
.add { color: var(--tech-success); font-weight: 600; }
.rm { color: var(--tech-danger); text-decoration: line-through; }
.chg { color: var(--tech-warn); font-weight: 600; }
</style>
