<template>
  <div class="dl-card">
    <div class="card-title"><span>采集管理</span><span class="role-tag">系统管理员</span></div>
    <div class="hint">对数据源创建采集任务，执行后采集表结构到库表元数据；结构变更生成新版本（不自动生效，需在「库表元数据」页应用）。</div>
    <div style="margin:10px 0;display:flex;gap:8px;align-items:center">
      <el-button size="small" type="primary" @click="openCreate">新建采集任务</el-button>
      <el-input v-model="kw" placeholder="任务名称模糊" size="small" style="width:200px" clearable @change="load" />
      <el-button size="small" @click="load">刷新</el-button>
    </div>
    <el-table :data="filtered" size="small" stripe border v-loading="loading">
      <el-table-column prop="name" label="任务名称" min-width="140" />
      <el-table-column label="数据源" min-width="150">
        <template #default="{ row }">{{ row.ds_name }}<span v-if="dsType(row.ds_id)" class="muted">({{ dsType(row.ds_id) }})</span></template>
      </el-table-column>
      <el-table-column prop="schema_filter" label="Schema过滤" width="110" show-overflow-tooltip />
      <el-table-column prop="table_filter" label="表过滤(空=全部)" min-width="130" show-overflow-tooltip />
      <el-table-column prop="cron" label="周期(秒)" width="85" />
      <el-table-column label="状态" width="95">
        <template #default="{ row }">
          <el-switch :model-value="row.status === 'ONLINE'" @change="(v) => toggleOnline(row, v)" inline-prompt active-text="上线" inactive-text="下线" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link size="small" type="success" :loading="runningId === row.id" @click="runOnce(row)">执行一次</el-button>
          <el-button link size="small" @click="openLog(row)">日志</el-button>
          <el-button link size="small" type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editDlg" :title="form.id ? '编辑采集任务' : '新建采集任务'" width="520px">
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="任务名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="form.ds_id" style="width:100%" filterable placeholder="选择层级下绑定的数据源">
            <el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Schema过滤"><el-input v-model="form.schema_filter" placeholder="留空=全部schema" /></el-form-item>
        <el-form-item label="表名过滤"><el-input v-model="form.table_filter" type="textarea" :rows="2" placeholder="逗号分隔精确表名；留空=一键采集该schema全部表" /></el-form-item>
        <el-form-item label="周期(秒)"><el-input v-model="form.cron" placeholder="如 300；留空=仅手动执行一次" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确定</el-button>
      </template>
    </el-dialog>

    <!-- 采集日志 -->
    <el-drawer v-model="logDlg" :title="`采集日志 - ${curJob?.name || ''}`" size="62%">
      <div style="margin-bottom:8px;display:flex;gap:8px">
        <el-button size="small" @click="clearLog('all')">清除全部</el-button>
        <el-button size="small" @click="clearLog('failed')">清除失败</el-button>
        <el-button size="small" @click="clearLog('before7d')">清除7天前</el-button>
      </div>
      <el-table :data="runs" size="small" stripe border max-height="540">
        <el-table-column prop="start_time" label="开始时间" width="160" />
        <el-table-column label="结果" width="80">
          <template #default="{ row }"><el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="tables_total" label="总表" width="55" />
        <el-table-column prop="tables_added" label="新增" width="55" />
        <el-table-column prop="tables_changed" label="变化" width="55" />
        <el-table-column prop="tables_removed" label="移除" width="55" />
        <el-table-column prop="triggered_by" label="触发者" width="90" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link size="small" type="primary" :disabled="row.status !== 'SUCCESS'" @click="showResult(row)">采集结果</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 采集结果 -->
    <el-dialog v-model="resultDlg" title="采集结果（本次采集表清单）" width="660px">
      <el-table :data="resultRows" size="small" border max-height="440">
        <el-table-column prop="schema" label="Schema" width="100" />
        <el-table-column prop="table" label="表名" min-width="170" />
        <el-table-column label="变化" width="110">
          <template #default="{ row }"><el-tag size="small" :type="resultTag(row.change)">{{ row.change }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="65" />
        <el-table-column label="待应用" width="70"><template #default="{ row }">{{ row.pending ? '是' : '' }}</template></el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const kw = ref('')
const dsList = ref<any[]>([])
const editDlg = ref(false)
const form = ref<any>({})
const saving = ref(false)
const runningId = ref<number | null>(null)
const logDlg = ref(false)
const curJob = ref<any>(null)
const runs = ref<any[]>([])
const resultDlg = ref(false)
const resultRows = ref<any[]>([])

const filtered = computed(() => (!kw.value ? rows.value : rows.value.filter((r) => (r.name || '').includes(kw.value))))

async function load() {
  loading.value = true
  try { rows.value = await api.govMetaCollectJobs() } catch (e: any) { ElMessage.error(errMsg(e)) }
  finally { loading.value = false }
}
function dsType(dsId: number) { const d = dsList.value.find((x) => x.id === dsId); return d ? d.type : '' }
function openCreate() { form.value = { status: 'OFFLINE', schema_filter: '', table_filter: '', cron: '' }; editDlg.value = true }
function openEdit(row: any) { form.value = { ...row }; editDlg.value = true }
async function save() {
  if (!form.value.name) return ElMessage.warning('填任务名称')
  if (!form.value.ds_id) return ElMessage.warning('选数据源')
  saving.value = true
  try { await api.govSaveMetaCollectJob(form.value); ElMessage.success('已保存'); editDlg.value = false; await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function del(row: any) {
  try { await ElMessageBox.confirm(`删除采集任务「${row.name}」?`, '提示', { type: 'warning' }) } catch { return }
  try { await api.govDeleteMetaCollectJob(row.id); ElMessage.success('已删除'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function toggleOnline(row: any, v: boolean) {
  try {
    v ? await api.govMetaCollectOnline(row.id) : await api.govMetaCollectOffline(row.id)
    ElMessage.success(v ? '已上线' : '已下线'); await load()
  } catch (e: any) { ElMessage.error(errMsg(e)); await load() }
}
async function runOnce(row: any) {
  runningId.value = row.id
  try {
    const r: any = await api.govMetaCollectRun(row.id)
    ElMessage.success(`采集完成: 总${r.tablesTotal} 新增${r.tablesAdded} 变化${r.tablesChanged} 移除${r.tablesRemoved}`)
    await load()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { runningId.value = null }
}
async function openLog(row: any) {
  curJob.value = row; logDlg.value = true
  try { runs.value = await api.govMetaCollectRuns(row.id) } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function showResult(row: any) {
  try {
    const d: any = await api.govMetaCollectRunDetail(row.id)
    resultRows.value = d.detail ? (JSON.parse(d.detail) || []) : []
    resultDlg.value = true
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function clearLog(rule: string) {
  if (!curJob.value) return
  try {
    const r: any = await api.govMetaCollectClearRuns(curJob.value.id, rule)
    ElMessage.success(`已清除 ${r.deleted} 条`)
    runs.value = await api.govMetaCollectRuns(curJob.value.id)
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
function resultTag(c: string) {
  if (c === 'INIT') return 'success'
  if (c === 'MODIFIED') return 'warning'
  if (c && c.startsWith('ERROR')) return 'danger'
  return 'info'
}

onMounted(async () => { try { dsList.value = await api.daSources() } catch { /* */ } await load() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 8px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { color: var(--tech-text-muted); font-size: 13px; }
.muted { color: var(--tech-text-muted); font-size: 12px; margin-left: 2px; }
</style>
