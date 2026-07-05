<template>
  <div>
    <div class="dl-card">
      <div class="card-title">
        <span>离线数据接入</span>
        <div style="display:flex;align-items:center;gap:10px">
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新建任务</el-button>
        </div>
      </div>
      <el-table :data="jobs" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="name" label="任务名" min-width="130" />
        <el-table-column label="源 → 目标" min-width="240">
          <template #default="{ row }">
            <span class="muted">ds{{ row.source_ds_id }}/{{ row.source_table }}</span>
            <b style="margin:0 4px">→</b>
            <span>{{ row.target_db }}.{{ row.target_table }}</span>
          </template>
        </el-table-column>
        <el-table-column label="策略" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.strategy === 'INCREMENTAL' ? 'warning' : ''">{{ row.strategy === 'INCREMENTAL' ? '增量' : '全量' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="last_sync_value" label="水位" width="150" />
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="success" :loading="runningId === row.id" @click="run(row)">执行</el-button>
            <el-button size="small" link type="primary" @click="previewSource(row)">预览源</el-button>
            <el-button size="small" link type="primary" @click="runs(row)">历史</el-button>
            <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
            <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint"><el-icon><InfoFilled /></el-icon> 全量=truncate+insert；增量=按业务键 PRIMARY KEY 表去重 upsert（首次自动建表，列类型按源推断）。</div>
    </div>

    <el-dialog v-model="dlg" :title="form.id ? '编辑任务' : '新建任务'" width="540px">
      <el-form :model="form" label-width="84px">
        <el-form-item label="任务名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="源数据源">
          <el-select v-model="form.source_ds_id" style="width:100%" @change="onDs">
            <el-option v-for="d in dsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="源表"><el-input v-model="form.source_table" placeholder="schema.table 或 table" /></el-form-item>
        <el-form-item label="策略">
          <el-radio-group v-model="form.strategy">
            <el-radio value="FULL">全量</el-radio>
            <el-radio value="INCREMENTAL">增量</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.strategy === 'INCREMENTAL'" label="增量列"><el-input v-model="form.inc_column" placeholder="时间或自增列" /></el-form-item>
        <el-form-item v-if="form.strategy === 'INCREMENTAL'" label="业务唯一键"><el-input v-model="form.biz_key" placeholder="去重主键（留空取首列）" /></el-form-item>
        <el-form-item label="目标库"><el-input v-model="form.target_db" /></el-form-item>
        <el-form-item label="目标表"><el-input v-model="form.target_table" placeholder="自动建表" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ENABLED">启用</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewDlg" :title="`源预览 - ${current?.name || ''}`" width="780px">
      <el-table :data="previewRows" size="small" border max-height="440" v-loading="previewLoading">
        <el-table-column v-for="c in previewCols" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <el-dialog v-model="runsDlg" :title="`执行历史 - ${current?.name || ''}`" width="780px">
      <el-table :data="runRows" size="small" border max-height="440">
        <el-table-column prop="start_time" label="开始" width="160" />
        <el-table-column prop="end_time" label="结束" width="160" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="读/写" width="120">
          <template #default="{ row }">{{ row.rows_read }} / {{ row.rows_written }}</template>
        </el-table-column>
        <el-table-column prop="error_msg" label="错误" min-width="200" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled } from '@element-plus/icons-vue'
import { api, errMsg, type DataSourceRow, type OfflineJobRow } from '@/api'

const jobs = ref<OfflineJobRow[]>([])
const dsList = ref<DataSourceRow[]>([])
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const runningId = ref<number | null>(null)
const form = reactive<any>({ id: null, name: '', source_ds_id: null, source_table: '', target_db: 'ods', target_table: '', strategy: 'FULL', inc_column: '', biz_key: '', status: 'ENABLED' })

const previewDlg = ref(false)
const previewLoading = ref(false)
const previewCols = ref<string[]>([])
const previewRows = ref<any[]>([])
const runsDlg = ref(false)
const runRows = ref<any[]>([])
const current = ref<OfflineJobRow | null>(null)

async function load() {
  loading.value = true
  try {
    const [j, d] = await Promise.all([api.daOfflineJobs(), api.daSources()])
    jobs.value = j
    dsList.value = d
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}

function onDs() {}

function open(row?: OfflineJobRow) {
  Object.assign(form, { id: null, name: '', source_ds_id: dsList.value[0]?.id || null, source_table: '', target_db: 'ods', target_table: '', strategy: 'FULL', inc_column: '', biz_key: '', status: 'ENABLED' })
  if (row) Object.assign(form, { id: row.id, name: row.name, source_ds_id: row.source_ds_id, source_table: row.source_table, target_db: row.target_db, target_table: row.target_table, strategy: row.strategy, inc_column: row.inc_column || '', biz_key: row.biz_key || '', status: row.status })
  dlg.value = true
}

async function save() {
  if (!form.name || !form.source_ds_id || !form.source_table || !form.target_table) return ElMessage.warning('请补全：名称/源/源表/目标表')
  saving.value = true
  try { await api.daSaveOfflineJob({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: OfflineJobRow) {
  await ElMessageBox.confirm(`确定删除任务 ${row.name}？`, '提示', { type: 'warning' })
  try { await api.daDeleteOfflineJob(row.id); ElMessage.success('已删除'); await load() } catch (e) { ElMessage.error(errMsg(e)) }
}

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
  current.value = row
  previewDlg.value = true
  previewLoading.value = true
  try {
    const r: any = await api.daOfflinePreview({ source_ds_id: row.source_ds_id, source_table: row.source_table, limit: 50 })
    previewCols.value = r.columns || []
    previewRows.value = r.rows || []
  } catch (e: any) { previewCols.value = []; previewRows.value = []; ElMessage.error(errMsg(e, '预览失败：检查源表名/驱动')) }
  finally { previewLoading.value = false }
}

async function runs(row: OfflineJobRow) {
  current.value = row
  runsDlg.value = true
  try { runRows.value = await api.daOfflineRuns(row.id) } catch (e: any) { runRows.value = []; ElMessage.error(errMsg(e)) }
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
