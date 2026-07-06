<template>
  <div>
    <!-- ROUTINE LOAD 概览 -->
    <div class="dl-card" style="margin-bottom:14px">
      <div class="card-title"><span>StarRocks ROUTINE LOAD 状态</span><el-button size="small" link @click="loadRl">刷新</el-button></div>
      <div v-if="!rls.length" class="muted">暂无 ROUTINE LOAD 作业</div>
      <div style="display:flex;flex-wrap:wrap;gap:10px">
        <el-tag v-for="r in rls" :key="r.Name" :type="rlType(r.State)" size="large">
          {{ r.Name }} · {{ r.State }}
        </el-tag>
      </div>
    </div>

    <div class="dl-card">
      <div class="card-title">
        <span>实时数据接入</span>
        <div style="display:flex;align-items:center;gap:10px">
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新建作业</el-button>
        </div>
      </div>
      <el-table :data="jobs" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="name" label="作业名" min-width="120" />
        <el-table-column label="类型" width="140">
          <template #default="{ row }"><el-tag size="small" :type="row.type === 'KAFKA_TO_SR' ? 'success' : 'warning'">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column label="流向" min-width="220">
          <template #default="{ row }">
            <span v-if="row.type === 'KAFKA_TO_SR'">topic <b>{{ row.kafka_topic }}</b> → {{ row.target_db }}.{{ row.target_table }}</span>
            <span v-else>ds{{ row.source_ds_id }} → topic <b>{{ row.kafka_topic }}</b> ({{ row.schedule_cron }}s)</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'RUNNING' ? 'success' : 'info'">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'RUNNING'" size="small" link type="success" @click="start(row)">启动</el-button>
            <el-button v-else size="small" link type="warning" @click="stop(row)">停止</el-button>
            <el-button size="small" link type="primary" @click="status(row)">状态</el-button>
            <el-button size="small" link type="primary" @click="runs(row)">历史</el-button>
            <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint"><el-icon><InfoFilled /></el-icon> KAFKA_TO_SR 建表+ROUTINE LOAD（topic→StarRocks，字段同名自动映射）；JDBC_TO_KAFKA 按秒轮询源 SQL 投递 Kafka（非真 CDC，毫秒级留 Phase B）。</div>
    </div>

    <el-dialog v-model="dlg" :title="form.id ? '编辑作业' : '新建作业'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="作业名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type">
            <el-radio value="KAFKA_TO_SR">Kafka → StarRocks</el-radio>
            <el-radio value="JDBC_TO_KAFKA">JDBC → Kafka</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.type === 'KAFKA_TO_SR'">
          <el-form-item label="Kafka Topic"><el-input v-model="form.kafka_topic" /></el-form-item>
          <el-form-item label="目标库"><el-input v-model="form.target_db" /></el-form-item>
          <el-form-item label="目标表"><el-input v-model="form.target_table" placeholder="自动建表" /></el-form-item>
          <el-form-item label="列定义">
            <el-input v-model="form.columns_json" type="textarea" :rows="3" placeholder='[{"col":"batch_no","type":"VARCHAR(50)"},{"col":"ts","type":"DATETIME"}]' />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="源数据源">
            <el-select v-model="form.source_ds_id" style="width:100%">
              <el-option v-for="d in dsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="源SQL"><el-input v-model="form.source_query" type="textarea" :rows="2" placeholder="SELECT * FROM 库名.表名" /></el-form-item>
          <el-form-item label="Kafka Topic"><el-input v-model="form.kafka_topic" /></el-form-item>
          <el-form-item label="轮询间隔(秒)"><el-input v-model="form.schedule_cron" placeholder="30" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="runsDlg" :title="`执行历史 - ${current?.name || ''}`" width="780px">
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled } from '@element-plus/icons-vue'
import { api, errMsg, type StreamJobRow, type DataSourceRow } from '@/api'

const rlType = (s: string) => (s === 'RUNNING' ? 'success' : s === 'PAUSED' || s === 'STOPPED' ? 'warning' : 'info')

const jobs = ref<StreamJobRow[]>([])
const dsList = ref<DataSourceRow[]>([])
const rls = ref<any[]>([])
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const runsDlg = ref(false)
const runRows = ref<any[]>([])
const current = ref<StreamJobRow | null>(null)
const form = reactive<any>({ id: null, name: '', type: 'KAFKA_TO_SR', source_ds_id: null, source_query: '', kafka_topic: '', target_db: 'ods', target_table: '', columns_json: '', schedule_cron: '30' })

async function load() {
  loading.value = true
  try {
    const [j, d] = await Promise.all([api.daStreamJobs(), api.daSources()])
    jobs.value = j
    dsList.value = d
    await loadRl()
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}

async function loadRl() {
  try { rls.value = await api.daRoutineLoads() } catch { rls.value = [] }
}

function open(row?: StreamJobRow) {
  Object.assign(form, { id: null, name: '', type: 'KAFKA_TO_SR', source_ds_id: dsList.value[0]?.id || null, source_query: '', kafka_topic: '', target_db: 'ods', target_table: '', columns_json: '', schedule_cron: '30' })
  if (row) Object.assign(form, { id: row.id, name: row.name, type: row.type, source_ds_id: row.source_ds_id, source_query: row.source_query || '', kafka_topic: row.kafka_topic, target_db: row.target_db || 'ods', target_table: row.target_table || '', columns_json: row.columns_json || '', schedule_cron: row.schedule_cron || '30' })
  dlg.value = true
}

async function save() {
  if (!form.name) return ElMessage.warning('请填作业名')
  saving.value = true
  try { await api.daSaveStreamJob({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: StreamJobRow) {
  await ElMessageBox.confirm(`确定删除作业 ${row.name}？`, '提示', { type: 'warning' })
  try { await api.daDeleteStreamJob(row.id); ElMessage.success('已删除'); await load() } catch (e) { ElMessage.error(errMsg(e)) }
}

async function start(row: StreamJobRow) {
  try { await api.daStreamStart(row.id); ElMessage.success('已启动'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function stop(row: StreamJobRow) {
  try { await api.daStreamStop(row.id); ElMessage.success('已停止'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function status(row: StreamJobRow) {
  try { const r: any = await api.daStreamStatus(row.id); ElMessageBox.alert(`状态：${r.state}`, '作业状态') } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function runs(row: StreamJobRow) {
  current.value = row; runsDlg.value = true
  try { runRows.value = await api.daStreamRuns(row.id) } catch { runRows.value = [] }
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
