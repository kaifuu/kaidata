<template>
  <div class="studio-root">
    <!-- 顶部工具栏 -->
    <div class="studio-toolbar">
      <div class="tb-left">
        <el-button size="small" @click="goBack">← 返回</el-button>
        <el-input v-model="taskName" size="small" style="width:220px" placeholder="任务名" @input="markDirty" />
        <el-tag size="small" :type="jobTypeTag" effect="dark">{{ JOB_TYPE_LABEL[jobType] }}</el-tag>
      </div>
      <div class="tb-right">
        <el-button size="small" @click="onValidate">校验</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="onSave">保存</el-button>
        <el-button size="small" type="success" :loading="running" @click="onRun">运行</el-button>
      </div>
    </div>

    <!-- 主体：DagEditor 撑满（左节点面板 + 画布 + 右属性面板） -->
    <div class="studio-body">
      <DagEditor ref="dagRef" v-model="dagJson" :job-type="jobType" />
    </div>

    <!-- 底部状态栏 -->
    <div class="studio-statusbar">
      <span>节点: {{ stats.nodes }}</span>
      <span>连线: {{ stats.edges }}</span>
      <span :class="validity.ok ? 'ok' : 'err'">
        {{ validity.errors.length ? validity.errors[0] : (stats.nodes ? '校验通过' : '未编排') }}
      </span>
      <span class="engine-hint">{{ engineHint }}</span>
      <span v-if="dirty" class="dirty">*未保存</span>
    </div>

    <!-- 运行日志 drawer -->
    <el-drawer v-model="logDlg" title="运行日志" size="55%" :destroy-on-close="true">
      <div style="margin-bottom:6px">
        <el-button size="small" @click="loadRuns">刷新</el-button>
        <el-button size="small" @click="clearRuns('all')">清全部</el-button>
        <el-button size="small" @click="clearRuns('failed')">清失败</el-button>
      </div>
      <el-table :data="runs" size="small" border max-height="220">
        <el-table-column prop="start_time" label="开始" width="150" />
        <el-table-column prop="status" label="状态" width="70" />
        <el-table-column prop="rows_read" label="行" width="55" />
        <el-table-column prop="engine_job_id" label="引擎作业ID" width="150" show-overflow-tooltip />
        <el-table-column prop="error_msg" label="说明" show-overflow-tooltip />
        <el-table-column label="操作" width="70">
          <template #default="{ row }"><el-button link size="small" @click="showLog(row)">查看</el-button></template>
        </el-table-column>
      </el-table>
      <pre v-if="logText" class="log-box">{{ logText }}</pre>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
import DagEditor from './DagEditor.vue'

const route = useRoute()
const router = useRouter()

const taskId = computed(() => Number(route.query.taskId) || 0)
const jobType = computed(() => (route.query.jobType as string) || 'flink_dag')
const JOB_TYPE_LABEL: any = { flink_dag: 'Flink图形化', kettle_hop: 'Kettle/Hop' }
const jobTypeTag: any = { flink_dag: 'info', kettle_hop: 'danger' }

const dagRef = ref<any>()
const taskName = ref('')
const dagJson = ref('')
const taskForm = ref<any>({})
const dirty = ref(false)
const saving = ref(false)
const running = ref(false)
const validity = ref<{ ok: boolean; errors: string[]; warnings: string[] }>({ ok: false, errors: [], warnings: [] })
const stats = ref({ nodes: 0, edges: 0 })
const engineHint = computed(() =>
  jobType.value === 'flink_dag' ? '引擎: Flink SQL Gateway (8083)' : '引擎: 绑定数据源 SQL (Kettle/Hop)'
)

// 日志
const logDlg = ref(false)
const runs = ref<any[]>([])
const logText = ref('')

function markDirty() { dirty.value = true }
watch(dagJson, () => { markDirty(); updateStats() })

function updateStats() {
  try {
    const obj = JSON.parse(dagJson.value || '{}')
    stats.value = { nodes: obj.nodes?.length || 0, edges: obj.edges?.length || 0 }
  } catch { /* */ }
}

function goBack() {
  if (dirty.value && !confirm('有未保存更改，确定返回？')) return
  router.push('/data-dev/offline')
}

// —— 数据加载 ——
onMounted(async () => {
  if (!taskId.value) { ElMessage.error('缺少 taskId，请从离线开发进入'); return }
  try {
    const d: any = await api.devOfflineTaskDetail(taskId.value)
    taskForm.value = { ...d, config: parseJson(d.config_json) }
    taskName.value = d.name || ''
    dagJson.value = d.dag_json || ''
    updateStats()
  } catch (e: any) { ElMessage.error(errMsg(e)) }
})

function parseJson(s: any): any { try { return typeof s === 'string' && s ? JSON.parse(s) : {} } catch { return {} } }

// —— 校验 ——
function onValidate() {
  if (!dagRef.value) return
  validity.value = dagRef.value.validate()
  if (validity.value.ok) {
    ElMessage.success('校验通过')
  } else {
    ElMessage.warning(validity.value.errors.join('; '))
  }
}

// —— 保存（返回是否成功，供 onRun 复用）——
async function onSave(): Promise<boolean> {
  if (!taskName.value) { ElMessage.warning('填任务名'); return false }
  onValidate()
  if (!validity.value.ok && !confirm('校验未通过，仍要保存？')) return false
  saving.value = true
  const payload: any = {
    id: taskId.value, name: taskName.value,
    catalog_id: taskForm.value.catalog_id, datasource_id: taskForm.value.datasource_id,
    job_type: jobType.value, sql_content: taskForm.value.sql_content || '',
    dag_json: dagJson.value || '',
    config_json: JSON.stringify(taskForm.value.config || {}),
    cron: taskForm.value.cron || '', status: taskForm.value.status || 'OFFLINE'
  }
  try {
    await api.devSaveOfflineTask(payload)
    ElMessage.success('已保存'); dirty.value = false
    return true
  } catch (e: any) { ElMessage.error(errMsg(e)); return false } finally { saving.value = false }
}

// —— 运行：先校验 → 保存 → 执行 ——
async function onRun() {
  onValidate()
  if (!validity.value.ok) { ElMessage.warning('请先修复校验错误'); return }
  if (!(await onSave())) return
  running.value = true
  try {
    const r: any = await api.devRunOffline(taskId.value)
    r.status === 'SUCCESS'
      ? ElMessage.success(`执行成功 rows=${r.rowsRead} ${r.engineJobId ? 'job=' + r.engineJobId : ''}`)
      : ElMessage.error(`失败: ${r.msg || r.error_msg || '未知错误'}`)
    logDlg.value = true
    await loadRuns()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { running.value = false }
}

async function loadRuns() {
  try { runs.value = await api.devOfflineRuns(taskId.value) } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function showLog(row: any) {
  try { const d: any = await api.devOfflineRunDetail(row.id); logText.value = d.log_text || d.error_msg || '(空)' }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function clearRuns(rule: string) {
  try { const r: any = await api.devOfflineClearRuns(taskId.value, rule); ElMessage.success(`已清 ${r.deleted}`); await loadRuns() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
</script>

<style scoped>
.studio-root { display: flex; flex-direction: column; height: 100vh; background: var(--tech-bg-2); overflow: hidden; }

.studio-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 12px; height: 44px; flex-shrink: 0;
  background: var(--tech-panel);
  border-bottom: 1px solid var(--tech-panel-border);
}
.tb-left, .tb-right { display: flex; align-items: center; gap: 8px; }

.studio-body { flex: 1; min-height: 0; overflow: hidden; padding: 8px; }
.studio-body :deep(.dag-wrap) { height: 100%; }

.studio-statusbar {
  display: flex; align-items: center; gap: 16px;
  padding: 0 12px; height: 28px; flex-shrink: 0;
  font-size: 12px; color: var(--tech-text-muted);
  background: var(--tech-panel);
  border-top: 1px solid var(--tech-panel-border);
}
.studio-statusbar .ok { color: var(--tech-success); }
.studio-statusbar .err { color: var(--tech-danger); }
.studio-statusbar .dirty { color: var(--tech-warn); }
.studio-statusbar .engine-hint { margin-left: auto; }

.log-box { background: var(--tech-bg); color: var(--tech-text-muted); padding: 10px; margin-top: 10px;
  font-size: 12px; white-space: pre-wrap; max-height: 300px; overflow: auto; border-radius: 4px; border: 1px solid var(--tech-panel-border); }
</style>
