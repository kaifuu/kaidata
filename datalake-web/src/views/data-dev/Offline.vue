<template>
  <div class="offline-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Coin /></el-icon></span>
        <div>
          <div class="page-title">离线开发</div>
          <div class="page-sub">批量 ETL · 流计算 · 图形化编排</div>
        </div>
      </div>
      <span class="role-tag">系统管理员</span>
    </div>

    <el-row :gutter="14" class="offline-body">
      <!-- 左：分类树 -->
      <el-col :span="5">
        <div class="dl-card cat-card">
          <div class="card-head">
            <span class="card-head-title"><i class="cat-dot" />分类</span>
            <el-button size="small" type="primary" plain @click="openCatDlg()">
              <el-icon><Plus /></el-icon>&nbsp;新建
            </el-button>
          </div>
          <el-input v-model="catKw" size="small" placeholder="筛选分类…" clearable class="cat-search">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <div class="tree-scroll">
            <el-tree ref="treeRef" :data="treeData" :props="{ label: 'name' }" node-key="id" highlight-current
                     :expand-on-click-node="false" default-expand-all :filter-node-method="catFilter" @node-click="onCatalog">
              <template #default="{ data: n }">
                <div class="cat-node" :class="{ active: curCatalog && curCatalog.id === n.id }">
                  <span class="cat-name" :title="n.name">{{ n.name }}</span>
                  <span class="cat-ops">
                    <el-icon class="op-edit" title="编辑" @click.stop="openCatDlg(n)"><EditPen /></el-icon>
                    <el-icon class="op-del" title="删除" @click.stop="delCat(n)"><Delete /></el-icon>
                  </span>
                </div>
              </template>
            </el-tree>
            <div v-if="!treeData.length" class="cat-empty hint">暂无分类，点"新建"创建</div>
          </div>
          <div class="cat-foot hint">选中分类后可新建任务</div>
        </div>
      </el-col>

      <!-- 右：任务列表 -->
      <el-col :span="19">
        <div class="dl-card list-card">
          <div class="card-head">
            <span class="card-head-title">任务列表</span>
            <span v-if="curCatalog" class="cur-cat">当前分类：<b>{{ curCatalog.name }}</b></span>
            <span class="count-badge">共 <b>{{ tasks.length }}</b> 个</span>
          </div>
          <div class="dl-toolbar">
            <el-button type="primary" :disabled="!curCatalog" @click="openTaskDlg()">
              <el-icon><Plus /></el-icon>&nbsp;新建任务
            </el-button>
            <el-input v-model="kw" placeholder="搜索任务名" clearable style="width: 200px" @change="loadTasks">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-select v-model="jobTypeFilter" placeholder="作业类型" clearable style="width: 140px" @change="loadTasks">
              <el-option v-for="(lbl, k) in JOB_TYPE_LABEL" :key="k" :label="lbl" :value="k" />
            </el-select>
            <div class="toolbar-actions">
              <el-button :icon="Refresh" @click="loadTasks">刷新</el-button>
            </div>
          </div>
          <el-table :data="tasks" stripe v-loading="loading" class="task-table">
            <el-table-column label="任务名" min-width="150">
              <template #default="{ row }">
                <span class="task-name">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="tagType(row.job_type)" effect="light">{{ JOB_TYPE_LABEL[row.job_type] || row.job_type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="调度周期" width="110">
              <template #default="{ row }">
                <span v-if="row.cron" class="cron-on"><el-icon><Clock /></el-icon>&nbsp;每 {{ row.cron }}s</span>
                <span v-else class="cron-off">手动</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-switch :model-value="row.status === 'ONLINE'" @change="(v) => toggle(row, v)" inline-prompt active-text="上线" inactive-text="下线" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-tooltip :content="isDag(row.job_type) ? '设置' : '编辑'" placement="top">
                    <el-button link :icon="Setting" @click="openTaskDlg(row)" />
                  </el-tooltip>
                  <el-tooltip v-if="isDag(row.job_type)" content="图形化编排" placement="top">
                    <el-button link type="warning" :icon="Share" @click="goStudio(row)" />
                  </el-tooltip>
                  <el-tooltip content="执行一次" placement="top">
                    <el-button link type="success" :icon="VideoPlay" :loading="running === row.id" @click="runOnce(row)" />
                  </el-tooltip>
                  <el-tooltip content="运行日志" placement="top">
                    <el-button link :icon="Document" @click="openLog(row)" />
                  </el-tooltip>
                  <el-tooltip content="删除" placement="top">
                    <el-button link type="danger" :icon="Delete" @click="delTask(row)" />
                  </el-tooltip>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <div class="table-empty">
                <el-icon class="empty-ic"><FolderOpened /></el-icon>
                <div>{{ curCatalog ? '该分类下暂无任务' : '请先在左侧选择分类' }}</div>
              </div>
            </template>
          </el-table>
        </div>
      </el-col>
    </el-row>

    <!-- 分类弹窗 -->
    <el-dialog v-model="catDlg" :title="catForm.id ? '编辑分类' : '新建分类'" width="400px">
      <el-form :model="catForm" label-width="80px" size="small">
        <el-form-item label="名称"><el-input v-model="catForm.name" /></el-form-item>
        <el-form-item label="父分类"><el-select v-model="catForm.parent_id" clearable style="width:100%"><el-option label="(顶级)" :value="0" /><el-option v-for="c in flatCats" :key="c.id" :label="c.name" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="排序"><el-input v-model.number="catForm.sort" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="catDlg = false">取消</el-button><el-button type="primary" @click="saveCat">保存</el-button></template>
    </el-dialog>

    <!-- 任务 drawer（按 job_type 切换编辑器）-->
    <el-drawer v-model="taskDlg" :title="taskForm.id ? '编辑任务' : '新建任务'" size="60%" :destroy-on-close="false">
      <el-form :model="taskForm" label-width="110px" size="small" class="task-form">
        <el-form-item label="任务名"><el-input v-model="taskForm.name" /></el-form-item>
        <el-form-item label="分类"><el-select v-model="taskForm.catalog_id" style="width:100%"><el-option v-for="c in flatCats" :key="c.id" :label="c.name" :value="c.id" /></el-select></el-form-item>

        <div class="section-label">作业类型</div>
        <div class="jt-grid">
          <div v-for="(lbl, k) in JOB_TYPE_LABEL" :key="k" class="jt-card" :class="{ active: taskForm.job_type === k }" @click="taskForm.job_type = k">
            <div class="jt-card-head">
              <span class="jt-ic">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path v-for="(d, i) in JOB_TYPE_ICON[k]" :key="i" :d="d" />
                </svg>
              </span>
              <span class="jt-label">{{ lbl }}</span>
            </div>
            <div class="jt-hint">{{ JOB_TYPE_HINT[k] }}</div>
          </div>
        </div>

        <el-form-item label="调度周期"><el-input v-model="taskForm.cron" placeholder="如 300（秒）；留空 = 仅手动执行" /></el-form-item>

        <!-- JdbcSQL / FlinkSQL：SQL 编辑 -->
        <template v-if="taskForm.job_type === 'jdbc_sql' || taskForm.job_type === 'flink_sql'">
          <div class="section-label">SQL 定义</div>
          <el-form-item v-if="taskForm.job_type === 'jdbc_sql'" label="数据源">
            <el-select v-model="taskForm.datasource_id" filterable style="width:100%"><el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" /></el-select>
          </el-form-item>
          <el-form-item label="SQL">
            <el-input v-model="taskForm.sql_content" type="textarea" :rows="12" class="mono" :placeholder="taskForm.job_type==='flink_sql' ? 'FlinkSQL：CREATE TABLE ...; INSERT INTO ...' : 'SELECT ...'" />
          </el-form-item>
          <el-form-item v-if="taskForm.job_type === 'flink_sql'" label="并行度"><el-input-number v-model="taskForm.config.parallelism" :min="1" :max="64" /></el-form-item>
        </template>

        <!-- FlinkJar -->
        <template v-if="taskForm.job_type === 'flink_jar'">
          <div class="section-label">Jar 作业</div>
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
          <div class="section-label">图形化编排</div>
          <el-form-item v-if="taskForm.job_type === 'kettle_hop'" label="执行数据源">
            <el-select v-model="taskForm.datasource_id" filterable style="width:100%">
              <el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
            </el-select>
            <div class="hint">DAG 将翻译为 SQL 在此数据源（通常 StarRocks）执行</div>
          </el-form-item>
          <el-form-item v-if="taskForm.job_type === 'flink_dag'" label="并行度"><el-input-number v-model="taskForm.config.parallelism" :min="1" :max="64" /></el-form-item>
          <div class="studio-callout">
            <div class="studio-callout-main">
              <el-icon class="studio-ic"><Connection /></el-icon>
              <div>
                <div class="studio-title">{{ taskForm.dagJson ? '已编排，可继续编辑' : '尚未编排' }}</div>
                <div class="hint">{{ taskForm.job_type === 'flink_dag' ? '在 Flink 图形化工作台拖拽算子、自动翻译 FlinkSQL' : '在 Kettle/Hop 工作台编排，翻译为 .hpl 在数据源执行' }}</div>
              </div>
            </div>
            <el-button type="primary" @click="openStudio">{{ taskForm.id ? '打开图形化编辑器' : '保存并打开编辑器' }}</el-button>
          </div>
        </template>
      </el-form>
      <template #footer><el-button @click="taskDlg = false">取消</el-button><el-button type="primary" @click="saveTask">保存</el-button></template>
    </el-drawer>

    <!-- 日志 drawer -->
    <el-drawer v-model="logDlg" :title="`日志 - ${curTask?.name || ''}`" size="60%">
      <div class="log-toolbar"><el-button size="small" :icon="Delete" @click="clearLog('all')">清全部</el-button><el-button size="small" :icon="Delete" @click="clearLog('failed')">清失败</el-button></div>
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
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Refresh, EditPen, Delete, VideoPlay, Document, Setting, Share, Coin, Clock, FolderOpened, Connection } from '@element-plus/icons-vue'
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
// 作业类型图标（内联 SVG path，零依赖，随主题着色）
const JOB_TYPE_ICON: Record<string, string[]> = {
  jdbc_sql: ['M4 6h16v12H4z', 'M4 10h16', 'M4 14h16'],                              // 数据库
  flink_sql: ['M13 2L4 14h6l-1 8 9-12h-6z'],                                         // 闪电
  flink_jar: ['M3 7l9-4 9 4-9 4-9-4z', 'M3 7v10l9 4 9-4V7', 'M12 11v10'],            // jar 包
  flink_dag: ['M5 4h5v5H5z', 'M14 15h5v5h-5z', 'M7.5 9v3a3 3 0 0 0 3 3H14'],         // DAG 节点
  kettle_hop: ['M5 4h14l-2 7H7z', 'M9 11v6a3 3 0 0 0 6 0v-6'],                      // 漏斗
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

// 分类树筛选
const treeRef = ref<any>(null)
const catKw = ref('')
function catFilter(value: string, data: any) { return !value || (data.name || '').includes(value) }
watch(catKw, (v) => treeRef.value?.filter(v))

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
.offline-page { display: flex; flex-direction: column; gap: 12px; height: 100%; }

/* 页头 */
.page-head { display: flex; align-items: center; justify-content: space-between; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); letter-spacing: .3px; }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 3px 10px; border-radius: 12px; background: var(--el-fill-color-light); }

.offline-body { flex: 1; min-height: 0; }

/* 卡片头（分类 / 列表共用） */
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.cat-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--tech-primary); box-shadow: var(--tech-glow); }
.cur-cat { font-size: 12px; color: var(--tech-text-muted); margin-left: auto; }
.cur-cat b { color: var(--tech-text); }
.card-head .count-badge { margin-left: 0; }
.card-head .count-badge { margin-left: auto; }
.cur-cat + .count-badge { margin-left: 0; }

/* 分类卡片 */
.cat-card { display: flex; flex-direction: column; padding: 12px; height: 100%; }
.cat-search { margin-bottom: 10px; }
.tree-scroll { flex: 1; overflow: auto; min-height: 120px; max-height: calc(100vh - 250px); padding-right: 2px; }
.cat-foot { margin-top: 10px; padding-top: 8px; border-top: 1px dashed var(--tech-panel-border); text-align: center; }
.cat-empty { text-align: center; padding: 24px 0; }

/* 分类节点 */
.cat-node { display: flex; align-items: center; justify-content: space-between; width: 100%; padding: 5px 8px; border-radius: 6px; transition: background .15s; }
.cat-node:hover { background: var(--el-fill-color-light); }
.cat-node.active { background: color-mix(in srgb, var(--tech-primary) 12%, transparent); }
.cat-name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 13px; color: var(--tech-text); }
.cat-node.active .cat-name { color: var(--tech-primary); font-weight: 600; }
.cat-ops { display: none; gap: 8px; flex-shrink: 0; }
.cat-node:hover .cat-ops { display: inline-flex; }
.cat-ops .el-icon { font-size: 13px; cursor: pointer; color: var(--tech-text-muted); transition: color .15s; }
.cat-ops .op-edit:hover { color: var(--tech-primary); }
.cat-ops .op-del:hover { color: var(--tech-danger); }
/* el-tree 节点占满宽度、去掉默认 hover 底色干扰 */
.cat-card :deep(.el-tree-node__content) { height: 32px; padding-right: 4px; }
.cat-card :deep(.el-tree-node__content:hover) { background: transparent; }
.cat-card :deep(.el-tree-node.is-current > .el-tree-node__content) { background: transparent; }

/* 列表卡片 */
.list-card { padding: 12px; }
.list-card .dl-toolbar { margin-bottom: 12px; }
.task-table .task-name { font-weight: 600; color: var(--tech-text); }
.cron-on { display: inline-flex; align-items: center; font-size: 12px; color: var(--tech-success); }
.cron-off { font-size: 12px; color: var(--tech-text-muted); }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; color: var(--tech-text-muted); opacity: .6; }

/* 任务表单 */
.task-form .section-label { font-size: 12px; font-weight: 700; color: var(--tech-text-muted); margin: 18px 0 10px; padding-bottom: 6px; border-bottom: 1px dashed var(--tech-panel-border); letter-spacing: .3px; }
.task-form .section-label:first-of-type { margin-top: 4px; }

/* 作业类型选择卡片 */
.jt-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 10px; margin-bottom: 6px; }
.jt-card { border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 11px 12px; cursor: pointer; transition: border-color .15s, background .15s, box-shadow .15s; background: var(--tech-bg-2); }
.jt-card:hover { border-color: color-mix(in srgb, var(--tech-primary) 45%, transparent); }
.jt-card.active { border-color: var(--tech-primary); background: color-mix(in srgb, var(--tech-primary) 8%, var(--tech-bg-2)); box-shadow: 0 0 0 1px var(--tech-primary) inset; }
html.dark .jt-card.active { box-shadow: 0 0 0 1px var(--tech-primary) inset, 0 0 14px rgba(0, 224, 255, 0.25); }
.jt-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; }
.jt-ic { width: 30px; height: 30px; border-radius: 7px; display: inline-flex; align-items: center; justify-content: center; color: var(--tech-primary); background: color-mix(in srgb, var(--tech-primary) 14%, transparent); flex-shrink: 0; }
.jt-label { font-size: 13px; font-weight: 600; color: var(--tech-text); }
.jt-hint { font-size: 11px; color: var(--tech-text-muted); line-height: 1.45; }

/* 图形化编辑器入口 callout */
.studio-callout { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 4px 0 12px; padding: 12px 14px; border-radius: 8px; border: 1px dashed color-mix(in srgb, var(--tech-primary) 40%, transparent); background: color-mix(in srgb, var(--tech-primary) 7%, transparent); }
.studio-callout-main { display: flex; align-items: center; gap: 10px; }
.studio-ic { font-size: 22px; color: var(--tech-primary); }
.studio-title { font-size: 13px; font-weight: 600; color: var(--tech-text); }

.hint { color: var(--tech-text-muted); font-size: 12px; }
.mono :deep(textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; }

/* 日志 */
.log-toolbar { display: flex; gap: 8px; margin-bottom: 8px; }
.log-box { background: var(--el-fill-color-light); color: var(--tech-text-muted); padding: 10px; margin-top: 10px; font-size: 12px; white-space: pre-wrap; max-height: 300px; overflow: auto; border-radius: 6px; border: 1px solid var(--tech-panel-border); font-family: ui-monospace, Menlo, Consolas, monospace; }
</style>
