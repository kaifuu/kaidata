<template>
  <div class="dl-card">
    <div class="card-title"><span>离线开发</span><span class="role-tag">系统管理员</span></div>
    <el-row :gutter="12">
      <el-col :span="5">
        <div class="tree-head"><span>分类</span><el-button size="small" @click="openCatDlg()">+ 分类</el-button></div>
        <el-tree :data="treeData" :props="{ label: 'name' }" node-key="id" highlight-current :expand-on-click-node="false" default-expand-all @node-click="onCatalog">
          <template #default="{ data: n }">
            <span>{{ n.name }}
              <el-link style="font-size: 11px" @click.stop="openCatDlg(n)">编辑</el-link>
              <el-link style="font-size: 11px" type="danger" @click.stop="delCat(n)">删</el-link>
            </span>
          </template>
        </el-tree>
        <div class="hint" style="margin-top:8px">选中分类后可新建任务。</div>
      </el-col>
      <el-col :span="19">
        <div class="bar">
          <el-button size="small" type="primary" :disabled="!curCatalog" @click="openTaskDlg()">新建任务</el-button>
          <el-input v-model="kw" placeholder="任务名" size="small" style="width:160px" @change="loadTasks" />
          <el-select v-model="jobTypeFilter" placeholder="作业类型" clearable size="small" style="width:130px" @change="loadTasks">
            <el-option v-for="(lbl, k) in JOB_TYPE_LABEL" :key="k" :label="lbl" :value="k" />
          </el-select>
          <el-button size="small" @click="loadTasks">刷新</el-button>
        </div>
        <el-table :data="tasks" size="small" stripe border v-loading="loading">
          <el-table-column prop="name" label="任务名" min-width="130" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }"><el-tag size="small" :type="tagType(row.job_type)">{{ JOB_TYPE_LABEL[row.job_type] || row.job_type }}</el-tag></template>
          </el-table-column>
          <el-table-column label="周期(秒)" width="85" prop="cron" />
          <el-table-column label="状态" width="95">
            <template #default="{ row }"><el-switch :model-value="row.status === 'ONLINE'" @change="(v) => toggle(row, v)" inline-prompt active-text="上线" inactive-text="下线" /></template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="openTaskDlg(row)">{{ isDag(row.job_type) ? '设置' : '编辑' }}</el-button>
              <el-button v-if="isDag(row.job_type)" link size="small" type="warning" @click="goStudio(row)">编排</el-button>
              <el-button link size="small" type="success" :loading="running === row.id" @click="runOnce(row)">执行</el-button>
              <el-button link size="small" @click="openLog(row)">日志</el-button>
              <el-button link size="small" type="danger" @click="delTask(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-col>
    </el-row>

    <!-- 分类弹窗 -->
    <el-dialog v-model="catDlg" :title="catForm.id ? '编辑分类' : '新建分类'" width="380px">
      <el-form :model="catForm" label-width="80px" size="small">
        <el-form-item label="名称"><el-input v-model="catForm.name" /></el-form-item>
        <el-form-item label="父分类"><el-select v-model="catForm.parent_id" clearable style="width:100%"><el-option label="(顶级)" :value="0" /><el-option v-for="c in flatCats" :key="c.id" :label="c.name" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="排序"><el-input v-model.number="catForm.sort" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="catDlg = false">取消</el-button><el-button type="primary" @click="saveCat">保存</el-button></template>
    </el-dialog>

    <!-- 任务 drawer（按 job_type 切换编辑器）-->
    <el-drawer v-model="taskDlg" :title="taskForm.id ? '编辑任务' : '新建任务'" size="85%" :destroy-on-close="false">
      <el-form :model="taskForm" label-width="110px" size="small">
        <el-form-item label="任务名"><el-input v-model="taskForm.name" /></el-form-item>
        <el-form-item label="分类"><el-select v-model="taskForm.catalog_id" style="width:100%"><el-option v-for="c in flatCats" :key="c.id" :label="c.name" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="作业类型">
          <el-radio-group v-model="taskForm.job_type">
            <el-radio-button v-for="(lbl, k) in JOB_TYPE_LABEL" :key="k" :label="k">{{ lbl }}</el-radio-button>
          </el-radio-group>
          <div class="hint" style="margin-top:4px">{{ JOB_TYPE_HINT[taskForm.job_type] }}</div>
        </el-form-item>
        <el-form-item label="周期(秒)"><el-input v-model="taskForm.cron" placeholder="如 300；留空=仅手动" /></el-form-item>

        <!-- JdbcSQL / FlinkSQL：SQL 编辑 -->
        <template v-if="taskForm.job_type === 'jdbc_sql' || taskForm.job_type === 'flink_sql'">
          <el-form-item v-if="taskForm.job_type === 'jdbc_sql'" label="数据源">
            <el-select v-model="taskForm.datasource_id" filterable style="width:100%"><el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" /></el-select>
          </el-form-item>
          <el-form-item label="SQL">
            <el-input v-model="taskForm.sql_content" type="textarea" :rows="12" style="font-family:monospace" :placeholder="taskForm.job_type==='flink_sql' ? 'FlinkSQL：CREATE TABLE ...; INSERT INTO ...' : 'SELECT ...'" />
          </el-form-item>
          <el-form-item v-if="taskForm.job_type === 'flink_sql'" label="并行度"><el-input-number v-model="taskForm.config.parallelism" :min="1" :max="64" /></el-form-item>
        </template>

        <!-- FlinkJar -->
        <template v-if="taskForm.job_type === 'flink_jar'">
          <el-form-item label="jar 包">
            <el-select v-model="taskForm.config.jarName" filterable placeholder="选择已上传的 jar" style="width:55%">
              <el-option v-for="j in jarList" :key="j.name" :label="`${j.name} (${Math.round(j.size/1024)}KB)`" :value="j.name" />
            </el-select>
            <el-upload :show-file-list="false" :before-upload="onJarUpload" :http-request="() => {}" style="display:inline-block;margin-left:8px">
              <el-button size="small" type="primary">上传 jar</el-button>
            </el-upload>
          </el-form-item>
          <el-form-item label="主类 (main)"><el-input v-model="taskForm.config.mainClass" placeholder="com.demo.OrderStreamJob" /></el-form-item>
          <el-form-item label="程序参数"><el-input v-model="taskForm.config.args" placeholder="--bootstrap kafka:9092 --topic orders" /></el-form-item>
          <el-form-item label="并行度"><el-input-number v-model="taskForm.config.parallelism" :min="1" :max="64" /></el-form-item>
        </template>

        <!-- Flink图形化 / Kettle-Hop：元信息 + 跳转全屏编辑器 -->
        <template v-if="taskForm.job_type === 'flink_dag' || taskForm.job_type === 'kettle_hop'">
          <el-form-item v-if="taskForm.job_type === 'kettle_hop'" label="执行数据源">
            <el-select v-model="taskForm.datasource_id" filterable style="width:100%">
              <el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
            </el-select>
            <div class="hint">DAG 将翻译为 SQL 在此数据源（通常 StarRocks）执行</div>
          </el-form-item>
          <el-form-item v-if="taskForm.job_type === 'flink_dag'" label="并行度"><el-input-number v-model="taskForm.config.parallelism" :min="1" :max="64" /></el-form-item>
          <el-form-item label="作业图">
            <el-button type="primary" @click="openStudio">{{ taskForm.id ? '打开图形化编辑器' : '保存并打开编辑器' }}</el-button>
            <span class="hint" style="margin-left:8px">{{ taskForm.dagJson ? '已编排，可继续编辑' : '尚未编排' }}</span>
          </el-form-item>
        </template>
      </el-form>
      <template #footer><el-button @click="taskDlg = false">取消</el-button><el-button type="primary" @click="saveTask">保存</el-button></template>
    </el-drawer>

    <!-- 日志 drawer -->
    <el-drawer v-model="logDlg" :title="`日志 - ${curTask?.name || ''}`" size="60%">
      <div style="margin-bottom:6px"><el-button size="small" @click="clearLog('all')">清全部</el-button><el-button size="small" @click="clearLog('failed')">清失败</el-button></div>
      <el-table :data="runs" size="small" border max-height="220">
        <el-table-column prop="start_time" label="开始" width="150" />
        <el-table-column prop="status" label="状态" width="70" />
        <el-table-column prop="rows_read" label="行" width="55" />
        <el-table-column prop="engine_job_id" label="引擎作业ID" width="150" show-overflow-tooltip />
        <el-table-column prop="error_msg" label="说明" show-overflow-tooltip />
        <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link size="small" @click="showLog(row)">查看</el-button></template></el-table-column>
      </el-table>
      <pre v-if="logText" class="log-box">{{ logText }}</pre>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'

const router = useRouter()
const MODULE = 'OFFLINE'
const JOB_TYPE_LABEL: any = { jdbc_sql: 'JdbcSQL', flink_sql: 'FlinkSQL', flink_jar: 'FlinkJar', flink_dag: 'Flink图形化', kettle_hop: 'Kettle/Hop' }
const JOB_TYPE_HINT: any = {
  jdbc_sql: '在指定数据源上执行 SQL，适合批量 ETL',
  flink_sql: '提交 FlinkSQL 到 Flink SQL Gateway，适合流式计算',
  flink_jar: '上传并提交 Flink Jar 作业，适合复杂流处理',
  flink_dag: '图形化编排 DAG，自动翻译 FlinkSQL，可视化流作业',
  kettle_hop: '图形化编排 DAG，自动翻译 SQL，在数据源上执行'
}
function isDag(t: string) { return t === 'flink_dag' || t === 'kettle_hop' }
function tagType(t: string): any { return ({ jdbc_sql: '', flink_sql: 'success', flink_jar: 'warning', flink_dag: 'info', kettle_hop: 'danger' } as any)[t] || '' }

const treeData = ref<any[]>([])
const curCatalog = ref<any>(null)
const tasks = ref<any[]>([])
const loading = ref(false)
const kw = ref('')
const jobTypeFilter = ref('')
const catDlg = ref(false)
const catForm = ref<any>({})
const flatCats = ref<any[]>([])
const taskDlg = ref(false)
const taskForm = ref<any>({})
const dsList = ref<any[]>([])
const jarList = ref<any[]>([])
const running = ref<number | null>(null)
const logDlg = ref(false)
const curTask = ref<any>(null)
const runs = ref<any[]>([])
const logText = ref('')

function flatten(nodes: any[]): any[] { const out: any[] = []; for (const n of nodes || []) { out.push(n); if (n.children) out.push(...flatten(n.children)) } return out }
function parseJson(s: any): any { try { return typeof s === 'string' && s ? JSON.parse(s) : {} } catch { return {} } }

async function loadTree() { try { treeData.value = await api.devCatalogTree(MODULE); flatCats.value = flatten(treeData.value) } catch (e: any) { ElMessage.error(errMsg(e)) } }
function onCatalog(n: any) { curCatalog.value = n; loadTasks() }
async function loadTasks() { loading.value = true; try { tasks.value = await api.devOfflineTasks(curCatalog.value?.id, kw.value || undefined, jobTypeFilter.value || undefined) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function openCatDlg(n?: any) { catForm.value = n ? { ...n } : { parent_id: curCatalog.value?.id || 0, sort: 0, name: '' }; catDlg.value = true }
async function saveCat() { if (!catForm.value.name) return ElMessage.warning('填名称'); catForm.value.module_type = MODULE; try { await api.devSaveCatalog(catForm.value); ElMessage.success('已保存'); catDlg.value = false; await loadTree() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function delCat(n: any) { try { await ElMessageBox.confirm(`删除分类 ${n.name}?`) } catch { return } try { await api.devDeleteCatalog(n.id); ElMessage.success('已删除'); await loadTree() } catch (e: any) { ElMessage.error(errMsg(e)) } }

async function openTaskDlg(row?: any) {
  if (row) {
    try {
      const d: any = await api.devOfflineTaskDetail(row.id)
      taskForm.value = { ...d, config: parseJson(d.config_json), dagJson: d.dag_json || '' }
    } catch { taskForm.value = { ...row, config: {}, dagJson: '' } }
  } else {
    taskForm.value = { catalog_id: curCatalog.value?.id, datasource_id: dsList.value[0]?.id, job_type: 'jdbc_sql', cron: '', sql_content: '', dagJson: '', config: { parallelism: 2 }, status: 'OFFLINE' }
  }
  if (!taskForm.value.config) taskForm.value.config = {}
  if (taskForm.value.job_type === 'flink_jar') await loadJars()
  taskDlg.value = true
}
async function loadJars() { try { jarList.value = await api.devOfflineJarList() } catch { jarList.value = [] } }
async function onJarUpload(file: File) {
  try { const r: any = await api.devOfflineJarUpload(file); ElMessage.success('已上传: ' + r.jarName); taskForm.value.config.jarName = r.jarName; await loadJars() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
  return false
}
async function saveTask() {
  if (!taskForm.value.name) return ElMessage.warning('填任务名')
  const f = taskForm.value
  const payload: any = { id: f.id, name: f.name, catalog_id: f.catalog_id, datasource_id: f.datasource_id, job_type: f.job_type || 'jdbc_sql', sql_content: f.sql_content || '', dag_json: f.dagJson || '', config_json: JSON.stringify(f.config || {}), cron: f.cron || '', status: f.status || 'OFFLINE' }
  try { await api.devSaveOfflineTask(payload); ElMessage.success('已保存'); taskDlg.value = false; await loadTasks() } catch (e: any) { ElMessage.error(errMsg(e)) }
}

// 图形化作业：保存元信息（新建拿 id）→ 跳转全屏编辑器
async function openStudio() {
  if (!taskForm.value.name) return ElMessage.warning('先填任务名')
  const f = taskForm.value
  const payload: any = { id: f.id, name: f.name, catalog_id: f.catalog_id, datasource_id: f.datasource_id, job_type: f.job_type, sql_content: f.sql_content || '', dag_json: f.dagJson || '', config_json: JSON.stringify(f.config || {}), cron: f.cron || '', status: f.status || 'OFFLINE' }
  try {
    const r: any = await api.devSaveOfflineTask(payload)
    if (!f.id && r && r.id) taskForm.value.id = r.id       // 新建 → 后端返回 id
    ElMessage.success('元信息已保存，跳转编辑器')
    taskDlg.value = false
    router.push(`/dag-studio?taskId=${taskForm.value.id}&jobType=${f.job_type}`)
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}

// 已有 DAG 任务：从列表直接进全屏编辑器（不经 drawer）
function goStudio(row: any) {
  router.push(`/dag-studio?taskId=${row.id}&jobType=${row.job_type}`)
}
async function delTask(row: any) { try { await ElMessageBox.confirm(`删除任务 ${row.name}?`) } catch { return } try { await api.devDeleteOfflineTask(row.id); ElMessage.success('已删除'); await loadTasks() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function toggle(row: any, v: boolean) { try { v ? await api.devOfflineOnline(row.id) : await api.devOfflineOffline(row.id); ElMessage.success(v ? '已上线' : '已下线'); await loadTasks() } catch (e: any) { ElMessage.error(errMsg(e)); await loadTasks() } }
async function runOnce(row: any) { running.value = row.id; try { const r: any = await api.devRunOffline(row.id); r.status === 'SUCCESS' ? ElMessage.success(`执行成功 rows=${r.rowsRead} ${r.engineJobId ? 'job='+r.engineJobId : ''}`) : ElMessage.error(`失败: ${r.msg}`) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { running.value = null } }
async function openLog(row: any) { curTask.value = row; logDlg.value = true; logText.value = ''; try { runs.value = await api.devOfflineRuns(row.id) } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function showLog(row: any) { try { const d: any = await api.devOfflineRunDetail(row.id); logText.value = d.log_text || d.error_msg || '(空)' } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function clearLog(rule: string) { if (!curTask.value) return; try { const r: any = await api.devOfflineClearRuns(curTask.value.id, rule); ElMessage.success(`已清 ${r.deleted}`); runs.value = await api.devOfflineRuns(curTask.value.id) } catch (e: any) { ElMessage.error(errMsg(e)) } }

onMounted(async () => { try { dsList.value = await api.daSources() } catch { /* */ } await loadTree() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.tree-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; font-size: 13px; color: var(--tech-text-muted); }
.hint { color: var(--tech-text-muted); font-size: 12px; }
.bar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.log-box { background: rgba(0, 0, 0, 0.3); color: #9fe; padding: 10px; margin-top: 10px; font-size: 12px; white-space: pre-wrap; max-height: 300px; overflow: auto; border-radius: 4px; }
</style>
