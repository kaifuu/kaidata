<template>
  <div>
    <!-- ROUTINE LOAD 概览 -->
    <div class="dl-card" style="margin-bottom:14px">
      <div class="card-title">
        <span class="ct-left"><el-icon class="title-icon"><DataLine /></el-icon>StarRocks ROUTINE LOAD 概览</span>
        <el-button size="small" link @click="loadRl"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
      <div v-if="!rls.length" class="muted">暂无 ROUTINE LOAD 作业</div>
      <div v-else style="display:flex;flex-wrap:wrap;gap:10px">
        <div v-for="r in rls" :key="r.Name" class="rl-chip">
          <div class="rl-name">{{ r.Name }} · {{ r.DbName }}.{{ r.TableName }}</div>
          <div class="rl-line">
            <el-tag size="small" :type="rlType(r.State)">{{ r.State }}</el-tag>
            <span class="rl-stat">已入仓 <b>{{ parseStat(r.Statistic).loadedRows }}</b></span>
            <span v-if="parseStat(r.Statistic).errorRows > 0" class="rl-stat err">错误 <b>{{ parseStat(r.Statistic).errorRows }}</b></span>
          </div>
        </div>
      </div>
    </div>

    <!-- 主表 -->
    <div class="dl-card">
      <div class="card-title">
        <span class="ct-left"><el-icon class="title-icon"><Connection /></el-icon>实时数据接入</span>
        <div class="head-right">
          <span class="count-badge">共 <b>{{ filtered.length }}</b> 个作业</span>
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新建作业</el-button>
        </div>
      </div>

      <div class="dl-toolbar">
        <el-input v-model="kw" placeholder="作业名" size="small" clearable style="width:180px" />
        <el-select v-model="kwType" placeholder="类型" size="small" clearable style="width:170px">
          <el-option label="Kafka → StarRocks" value="KAFKA_TO_SR" />
          <el-option label="JDBC → Kafka" value="JDBC_TO_KAFKA" />
        </el-select>
        <el-select v-model="kwStatus" placeholder="状态" size="small" clearable style="width:120px">
          <el-option label="运行中" value="RUNNING" />
          <el-option label="已停止" value="STOPPED" />
        </el-select>
        <div class="toolbar-actions"><el-button size="small" @click="resetQuery">重置</el-button></div>
      </div>

      <el-table :data="paged" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="name" label="作业名" min-width="130" />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'KAFKA_TO_SR' ? 'success' : 'warning'">{{ row.type === 'KAFKA_TO_SR' ? 'Kafka → StarRocks' : 'JDBC → Kafka' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据流向" min-width="280">
          <template #default="{ row }">
            <span v-if="row.type === 'KAFKA_TO_SR'" class="flow-inline">
              <span class="fi-label">Kafka</span><b>{{ row.kafka_topic }}</b>
              <el-icon class="fi-arrow"><Right /></el-icon>
              <span class="fi-label">StarRocks</span><b>{{ row.target_db }}.{{ row.target_table }}</b>
            </span>
            <span v-else class="flow-inline">
              <span class="fi-label">源</span><b>{{ dsMap[row.source_ds_id] || ('ds#' + row.source_ds_id) }}</b>
              <el-icon class="fi-arrow"><Right /></el-icon>
              <span class="fi-label">Kafka</span><b>{{ row.kafka_topic }}</b>
              <span class="muted" style="margin-left:6px">{{ row.schedule_cron }}s</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'RUNNING' ? 'success' : 'info'">{{ row.status === 'RUNNING' ? '运行中' : '已停止' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button v-if="row.status !== 'RUNNING'" size="small" link type="success" @click="start(row)">启动</el-button>
              <el-button v-else size="small" link type="warning" @click="stop(row)">停止</el-button>
              <el-button size="small" link type="primary" @click="monitor(row)">监控</el-button>
              <el-button size="small" link type="primary" @click="preview(row)">预览</el-button>
              <el-button size="small" link type="primary" @click="runs(row)">历史</el-button>
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
      <div class="hint"><el-icon><InfoFilled /></el-icon> Kafka→StarRocks：启动即显式建 topic + ROUTINE LOAD（勾选主键列→主键模型去重）；JDBC→Kafka：按秒轮询源 SQL 投递 Kafka。监控面板可查 <b>已入仓行数/错误/消费进度</b>。</div>
    </div>

    <!-- 新建/编辑 -->
    <el-dialog v-model="dlg" :title="form.id ? '编辑作业' : '新建作业'" width="620px">
      <el-form :model="form" label-width="92px" size="default">
        <el-form-item label="作业名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type">
            <el-radio value="KAFKA_TO_SR">Kafka → StarRocks</el-radio>
            <el-radio value="JDBC_TO_KAFKA">JDBC → Kafka</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.type === 'KAFKA_TO_SR'">
          <el-form-item label="Kafka Topic"><el-input v-model="form.kafka_topic" placeholder="启动时自动显式创建" /></el-form-item>
          <el-form-item label="目标库"><el-input v-model="form.target_db" placeholder="ods" /></el-form-item>
          <el-form-item label="目标表"><el-input v-model="form.target_table" placeholder="自动建表" /></el-form-item>
          <el-form-item label="列定义">
            <div class="col-editor">
              <div v-for="(c, i) in form.cols" :key="i" class="col-row">
                <el-input v-model="c.col" placeholder="列名" size="small" style="width:150px" />
                <el-select v-model="c.type" size="small" filterable allow-create default-first-option style="width:150px">
                  <el-option v-for="t in COL_TYPES" :key="t" :label="t" :value="t" />
                </el-select>
                <el-checkbox v-model="c.pk">主键</el-checkbox>
                <el-button size="small" link type="danger" @click="form.cols.splice(i, 1)"><el-icon><Delete /></el-icon></el-button>
              </div>
              <el-button size="small" type="primary" plain @click="form.cols.push({ col: '', type: 'VARCHAR(255)', pk: false })"><el-icon><Plus /></el-icon> 添加列</el-button>
              <div class="muted" style="margin-top:4px">勾选主键→PRIMARY KEY 模型（重复键 upsert 去重）；不勾→DUPLICATE KEY（追加）</div>
            </div>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="源数据源">
            <el-select v-model="form.source_ds_id" style="width:100%">
              <el-option v-for="d in dsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="源SQL"><el-input v-model="form.source_query" type="textarea" :rows="2" placeholder="SELECT * FROM ods.表名" /></el-form-item>
          <el-form-item label="Kafka Topic"><el-input v-model="form.kafka_topic" /></el-form-item>
          <el-form-item label="轮询间隔(秒)"><el-input v-model="form.schedule_cron" placeholder="30" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 监控面板 -->
    <el-dialog v-model="monDlg" :title="`实时监控 - ${current?.name || ''}`" width="720px" @close="stopAuto">
      <div v-loading="monLoading">
        <div class="flow-diag">
          <div class="flow-node">
            <div class="fn-title">{{ current?.type === 'KAFKA_TO_SR' ? 'Kafka' : '源 (JDBC)' }}</div>
            <div class="fn-sub">{{ current?.type === 'KAFKA_TO_SR' ? current?.kafka_topic : (dsMap[current?.source_ds_id || 0] || '-') }}</div>
          </div>
          <template v-if="current?.type === 'JDBC_TO_KAFKA'">
            <el-icon class="flow-arrow"><Right /></el-icon>
            <div class="flow-node"><div class="fn-title">Kafka</div><div class="fn-sub">{{ current?.kafka_topic }}</div></div>
          </template>
          <el-icon class="flow-arrow"><Right /></el-icon>
          <div class="flow-node" v-if="current?.type === 'KAFKA_TO_SR'">
            <div class="fn-title">StarRocks</div>
            <div class="fn-sub">{{ current?.target_db }}.{{ current?.target_table }}</div>
          </div>
        </div>

        <div class="stat-grid" v-if="current?.type === 'KAFKA_TO_SR'">
          <div class="stat-cell"><div class="stat-label">作业状态</div><div class="stat-val"><el-tag size="small" :type="rlType(mon.state)">{{ mon.state || '-' }}</el-tag></div></div>
          <div class="stat-cell"><div class="stat-label">已入仓行数</div><div class="stat-val big">{{ mon.loadedRows }}</div></div>
          <div class="stat-cell"><div class="stat-label">目标表行数</div><div class="stat-val big">{{ mon.targetRows }}</div></div>
          <div class="stat-cell"><div class="stat-label">错误行数</div><div class="stat-val big" :class="{ err: mon.errorRows > 0 }">{{ mon.errorRows }}</div></div>
          <div class="stat-cell"><div class="stat-label">消费进度</div><div class="stat-val sm">{{ mon.progress || '-' }}</div></div>
          <div class="stat-cell"><div class="stat-label">接收字节</div><div class="stat-val sm">{{ mon.receivedBytes }}</div></div>
        </div>
        <div class="stat-grid" v-else>
          <div class="stat-cell"><div class="stat-label">运行态</div><div class="stat-val"><el-tag size="small" :type="mon.running ? 'success' : 'info'">{{ mon.running ? '轮询中' : '已停止' }}</el-tag></div></div>
          <div class="stat-cell"><div class="stat-label">Topic</div><div class="stat-val sm">{{ mon.topic || '-' }}</div></div>
          <div class="stat-cell"><div class="stat-label">最近投递(出)</div><div class="stat-val big">{{ mon.lastRowsOut ?? '-' }}</div></div>
          <div class="stat-cell"><div class="stat-label">最近状态</div><div class="stat-val"><el-tag size="small" :type="mon.lastStatus === 'SUCCESS' ? 'success' : 'danger'">{{ mon.lastStatus || '-' }}</el-tag></div></div>
        </div>

        <el-alert v-if="mon.reason" type="warning" :closable="false" show-icon :title="`状态变更：${mon.reason}`" style="margin-top:12px" />
        <el-alert v-if="mon.errorLogUrls" type="error" :closable="false" show-icon title="错误日志" style="margin-top:8px">
          <pre class="json-box" style="margin-top:6px;max-height:160px">{{ mon.errorLogUrls }}</pre>
        </el-alert>
        <el-alert v-if="current?.type === 'JDBC_TO_KAFKA' && mon.lastError" type="error" :closable="false" show-icon :title="mon.lastError" style="margin-top:8px" />
      </div>
      <template #footer>
        <span class="muted" style="float:left">{{ current?.status === 'RUNNING' ? '运行中每 3s 自动刷新' : '' }}</span>
        <el-button size="small" @click="loadStats">刷新</el-button>
        <el-button @click="monDlg = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 数据预览（Tabs） -->
    <el-dialog v-model="prevDlg" :title="`数据预览 - ${current?.name || ''}`" width="860px">
      <el-tabs v-model="prevTab">
        <el-tab-pane label="Kafka 消息" name="kafka">
          <div v-loading="prevLoading">
            <div v-if="!topicMsgs.length" class="muted">暂无消息（topic 可能尚未有数据）</div>
            <pre v-else class="json-box" style="max-height:440px">{{ topicMsgs.join('\n') }}</pre>
          </div>
        </el-tab-pane>
        <el-tab-pane label="目标表样例" name="table">
          <div v-loading="prevLoading">
            <div v-if="!tableRows.length" class="muted">暂无数据（目标表尚未入仓，或仅 JDBC→Kafka 作业无目标表）</div>
            <el-table v-else :data="tableRows" size="small" border max-height="440">
              <el-table-column v-for="c in tableCols" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 执行历史 -->
    <el-dialog v-model="runsDlg" :title="`执行历史 - ${current?.name || ''}`" width="800px">
      <el-table :data="runRows" size="small" border max-height="440">
        <el-table-column prop="start_time" label="开始" width="160" />
        <el-table-column prop="end_time" label="结束" width="160" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="入/出" width="100"><template #default="{ row }">{{ row.rows_in }} / {{ row.rows_out }}</template></el-table-column>
        <el-table-column prop="error_msg" label="错误" min-width="200" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled, Delete, Right, DataLine, Connection, Refresh } from '@element-plus/icons-vue'
import { api, errMsg, type StreamJobRow, type DataSourceRow } from '@/api'

const COL_TYPES = ['VARCHAR(255)', 'VARCHAR(100)', 'VARCHAR(50)', 'BIGINT', 'INT', 'DOUBLE', 'DATE', 'DATETIME', 'BOOLEAN', 'DECIMAL(18,4)']
const rlType = (s: string) => s === 'RUNNING' ? 'success' : (s === 'PAUSED' || s === 'STOPPED' || s === 'NEED_SCHEDULE' ? 'warning' : (s === 'CANCELLED' ? 'danger' : 'info'))

const jobs = ref<StreamJobRow[]>([])
const dsList = ref<DataSourceRow[]>([])
const rls = ref<any[]>([])
const loading = ref(false)
const dlg = ref(false); const saving = ref(false)
const runsDlg = ref(false); const runRows = ref<any[]>([])
const monDlg = ref(false); const monLoading = ref(false)
const prevDlg = ref(false); const prevLoading = ref(false); const prevTab = ref('kafka')
const topicMsgs = ref<string[]>([]); const tableCols = ref<string[]>([]); const tableRows = ref<any[]>([])
const current = ref<StreamJobRow | null>(null)
const mon = reactive<any>({ state: '', loadedRows: 0, errorRows: 0, totalRows: 0, receivedBytes: 0, targetRows: 0, progress: '', reason: '', errorLogUrls: '', running: false, topic: '', lastRowsOut: null, lastStatus: '', lastError: '' })
const form = reactive<any>({ id: null, name: '', type: 'KAFKA_TO_SR', source_ds_id: null, source_query: '', kafka_topic: '', target_db: 'ods', target_table: '', cols: [], schedule_cron: '30' })

const dsMap = computed<Record<number, string>>(() => { const m: Record<number, string> = {}; dsList.value.forEach(d => { m[d.id] = `${d.name}(${d.type})` }); return m })

// 检索 + 分页（客户端）
const kw = ref(''); const kwType = ref(''); const kwStatus = ref('')
const page = reactive({ page: 1, size: 10 })
const filtered = computed(() => jobs.value.filter(j =>
  (!kw.value || (j.name || '').toLowerCase().includes(kw.value.toLowerCase())) &&
  (!kwType.value || j.type === kwType.value) &&
  (!kwStatus.value || j.status === kwStatus.value)))
const paged = computed(() => filtered.value.slice((page.page - 1) * page.size, page.page * page.size))
function resetQuery() { kw.value = ''; kwType.value = ''; kwStatus.value = ''; page.page = 1 }
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }

let autoTimer: any = null
function stopAuto() { if (autoTimer) { clearInterval(autoTimer); autoTimer = null } }
onBeforeUnmount(stopAuto)

function parseStat(s: any) {
  try { const o = typeof s === 'string' ? JSON.parse(s) : (s || {}); return { loadedRows: o.loadedRows || 0, errorRows: o.errorRows || 0 } }
  catch { return { loadedRows: 0, errorRows: 0 } }
}

async function load() {
  loading.value = true
  try {
    const [j, d] = await Promise.all([api.daStreamJobs(), api.daSources()])
    jobs.value = j; dsList.value = d
    await loadRl()
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}
async function loadRl() { try { rls.value = await api.daRoutineLoads() } catch { rls.value = [] } }

function colsToJson(cols: any[]) { return JSON.stringify((cols || []).map((c: any) => ({ col: c.col, type: c.type, pk: !!c.pk })).filter((c: any) => c.col)) }
function jsonToCols(s?: string) {
  try { const a = s ? JSON.parse(s) : []; return a.map((c: any) => ({ col: c.col || c.name || '', type: c.type || 'VARCHAR(255)', pk: !!c.pk })) }
  catch { return [] }
}

function open(row?: StreamJobRow) {
  Object.assign(form, { id: null, name: '', type: 'KAFKA_TO_SR', source_ds_id: dsList.value[0]?.id || null, source_query: '', kafka_topic: '', target_db: 'ods', target_table: '', cols: [{ col: 'id', type: 'BIGINT', pk: true }], schedule_cron: '30' })
  if (row) Object.assign(form, { id: row.id, name: row.name, type: row.type, source_ds_id: row.source_ds_id || null, source_query: row.source_query || '', kafka_topic: row.kafka_topic, target_db: row.target_db || 'ods', target_table: row.target_table || '', cols: jsonToCols(row.columns_json), schedule_cron: row.schedule_cron || '30' })
  dlg.value = true
}

async function save() {
  if (!form.name) return ElMessage.warning('请填作业名')
  if (form.type === 'KAFKA_TO_SR') {
    if (!form.kafka_topic || !form.target_db || !form.target_table) return ElMessage.warning('请补全：topic / 目标库 / 目标表')
    if (!form.cols.length || !form.cols.some((c: any) => c.col)) return ElMessage.warning('请至少配置一列')
  } else {
    if (!form.source_ds_id || !form.source_query || !form.kafka_topic) return ElMessage.warning('请补全：源 / 源SQL / topic')
  }
  saving.value = true
  try {
    const body: any = { ...form, columns_json: form.type === 'KAFKA_TO_SR' ? colsToJson(form.cols) : '' }
    delete body.cols
    await api.daSaveStreamJob(body); ElMessage.success('保存成功'); dlg.value = false; await load()
  } catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: StreamJobRow) {
  try { await ElMessageBox.confirm(`确定删除作业 ${row.name}？将同时停止并删除其历史。`, '提示', { type: 'warning' }) } catch { return }
  try { await api.daDeleteStreamJob(row.id); ElMessage.success('已删除'); await load() } catch (e) { ElMessage.error(errMsg(e)) }
}
async function start(row: StreamJobRow) { try { await api.daStreamStart(row.id); ElMessage.success('已启动（已显式建 topic）'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function stop(row: StreamJobRow) { try { await api.daStreamStop(row.id); ElMessage.success('已停止'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) } }

async function loadStats() {
  if (!current.value) return
  monLoading.value = true
  try { Object.assign(mon, await api.daStreamStats(current.value.id)) }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { monLoading.value = false }
}
function monitor(row: StreamJobRow) {
  current.value = row; monDlg.value = true
  Object.assign(mon, { state: '', loadedRows: 0, errorRows: 0, totalRows: 0, receivedBytes: 0, targetRows: 0, progress: '', reason: '', errorLogUrls: '', running: false, topic: '', lastRowsOut: null, lastStatus: '', lastError: '' })
  stopAuto()
  loadStats()
  if (row.status === 'RUNNING') autoTimer = setInterval(loadStats, 3000)
}

async function preview(row: StreamJobRow) {
  current.value = row; prevDlg.value = true; prevTab.value = 'kafka'; prevLoading.value = true
  try {
    const [msgs, tbl] = await Promise.all([
      api.daStreamTopicPreview(row.kafka_topic, 20).catch(() => []),
      (row.type === 'KAFKA_TO_SR' && row.target_db && row.target_table)
        ? api.daStreamTablePreview(row.target_db, row.target_table, 20).catch(() => ({ columns: [], rows: [] }))
        : Promise.resolve({ columns: [], rows: [] })
    ])
    topicMsgs.value = msgs as string[]; tableCols.value = (tbl as any).columns || []; tableRows.value = (tbl as any).rows || []
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { prevLoading.value = false }
}

async function runs(row: StreamJobRow) {
  current.value = row; runsDlg.value = true
  try { runRows.value = await api.daStreamRuns(row.id) } catch { runRows.value = [] }
}

onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.ct-left { display: inline-flex; align-items: center; }
.title-icon { margin-right: 6px; color: var(--tech-primary); font-size: 18px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.count-badge { font-size: 12px; color: var(--tech-text-muted); }
.count-badge b { color: var(--tech-primary); font-size: 14px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.hint b { color: var(--tech-primary); }
.json-box { background: var(--el-fill-color-light); padding: 12px; border-radius: 6px; max-height: 460px; overflow: auto; font-size: 12px; white-space: pre-wrap; color: var(--tech-text); }
.row-actions { display: flex; flex-wrap: wrap; gap: 2px; }

/* ROUTINE LOAD 概览 chip */
.rl-chip { border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 8px 12px; min-width: 200px; background: var(--el-fill-color-blank); }
.rl-name { font-weight: 600; margin-bottom: 4px; }
.rl-line { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.rl-stat { color: var(--tech-text-muted); }
.rl-stat b { color: var(--tech-text); }
.rl-stat.err b { color: var(--el-color-danger); }

/* 流向内联 */
.flow-inline { display: inline-flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.fi-label { color: var(--tech-text-muted); font-size: 12px; }
.fi-arrow { color: var(--tech-primary); margin: 0 4px; }

/* 列编辑器 */
.col-editor { width: 100%; }
.col-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }

/* 监控：数据流图 */
.flow-diag { display: flex; align-items: center; justify-content: center; gap: 14px; padding: 14px 0 18px; }
.flow-node { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 10px 16px; text-align: center; min-width: 130px; background: var(--el-fill-color-light); }
.fn-title { font-weight: 600; color: var(--tech-primary); }
.fn-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; max-width: 170px; word-break: break-all; }
.flow-arrow { font-size: 24px; color: var(--tech-primary); }

/* 监控：计数网格 */
.stat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.stat-cell { border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 10px 12px; }
.stat-label { font-size: 12px; color: var(--tech-text-muted); }
.stat-val { margin-top: 4px; }
.stat-val.sm { font-size: 13px; word-break: break-all; }
.stat-val.big { font-size: 20px; font-weight: 600; color: var(--tech-primary); }
.stat-val.err { color: var(--el-color-danger); }
</style>
