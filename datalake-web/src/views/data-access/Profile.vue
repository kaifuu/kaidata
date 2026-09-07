<template>
  <div>
    <!-- 顶部统计条 -->
    <el-row :gutter="12" class="stat-bar">
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-head"><b>{{ jobs.length }}</b><span class="muted">任务总数</span></div>
          <div class="stat-sub muted">周期 {{ statCron }} · 手动 {{ jobs.length - statCron }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-head"><b class="ok">{{ statOnline }}</b><span class="muted">已上线</span></div>
          <div class="stat-sub muted">已下线 {{ jobs.length - statOnline }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-head"><b>{{ statTables }}</b><span class="muted">探查表总数</span></div>
          <div class="stat-sub muted">覆盖数据源 {{ statDs }} 个</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-head"><b class="ok">{{ statLastOk }}</b><span class="muted">最近执行成功</span></div>
          <div class="stat-sub muted"><span :class="{ danger: statLastFail > 0 }">失败 {{ statLastFail }}</span> · 未执行 {{ statNever }}</div>
        </div>
      </el-col>
    </el-row>

    <div class="dl-card">
      <div class="card-title">
        <span class="ct-left"><el-icon class="title-icon"><DataAnalysis /></el-icon>数据探查任务</span>
        <div class="head-right">
          <span class="count-badge">共 <b>{{ filtered.length }}</b> 个任务</span>
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新建探查任务</el-button>
        </div>
      </div>
      <!-- 检索 -->
      <div class="dl-toolbar">
        <el-input v-model="kw" placeholder="任务名称" size="small" clearable style="width:180px" />
        <el-input v-model="kwCreator" placeholder="创建人" size="small" clearable style="width:130px" />
        <el-select v-model="kwStatus" placeholder="周期状态" size="small" clearable style="width:120px">
          <el-option label="已上线(ONLINE)" value="ONLINE" />
          <el-option label="已下线(OFFLINE)" value="OFFLINE" />
        </el-select>
        <div class="toolbar-actions">
          <el-button size="small" @click="resetQuery">重置</el-button>
        </div>
      </div>

      <el-table :data="paged" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="130" />
        <el-table-column prop="name" label="任务名" min-width="120" show-overflow-tooltip />
        <el-table-column label="源数据源" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ dsMap[row.source_ds_id] || ('ds#' + row.source_ds_id) }}</template>
        </el-table-column>
        <el-table-column prop="target_db" label="层级" width="70" />
        <el-table-column label="探查表" width="70" align="center">
          <template #default="{ row }"><b>{{ row.table_count ?? 0 }}</b></template>
        </el-table-column>
        <el-table-column label="周期" width="86">
          <template #default="{ row }">
            <el-tag v-if="row.cron" size="small">每{{ row.cron }}s</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">手动</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近执行" width="190">
          <template #default="{ row }">
            <template v-if="row.last_run_time">
              <div class="last-run-line">
                <el-tag size="small" :type="runTagType(row.last_status)">{{ runTagLabel(row.last_status) }}</el-tag>
                <span class="lr-time">{{ fmtShort(row.last_run_time) }}</span>
              </div>
              <div class="muted lr-sub">变 <b :class="{ chg: (row.last_changed || 0) > 0 }">{{ row.last_changed }}</b> / 共 {{ row.last_total }} 张</div>
            </template>
            <span v-else class="muted">未执行</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="84">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'ONLINE' ? 'success' : 'info'">{{ row.status === 'ONLINE' ? '已上线' : '已下线' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button size="small" link type="success" :loading="runningId === row.id" @click="run(row)">执行</el-button>
              <el-button v-if="row.status !== 'ONLINE'" size="small" link type="primary" @click="online(row)">上线</el-button>
              <el-button v-else size="small" link type="warning" @click="offline(row)">下线</el-button>
              <el-button size="small" link type="primary" @click="openRuns(row)">日志</el-button>
              <el-button size="small" link type="primary" @click="openRecords(row)">记录</el-button>
              <el-button size="small" link type="primary" :disabled="row.status === 'ONLINE'" @click="open(row)">编辑</el-button>
              <el-button size="small" link type="danger" :disabled="row.status === 'ONLINE'" @click="del(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="filtered.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper"
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
      <div class="hint"><el-icon><InfoFilled /></el-icon> 探查感知表结构变化形成版本；首次探查可自动建模到所选层级；勾选字段统计唯一值/空值/数值分布；<b>上线状态编辑/删除置灰</b>。</div>
    </div>

    <!-- 新建/编辑（右抽屉） -->
    <el-drawer v-model="dlg" :title="form.id ? '编辑探查任务' : '新建探查任务'" size="960px" destroy-on-close>
      <div class="edit-grid">
        <!-- 左：数据源 + 表勾选 -->
        <div class="pane">
          <div class="sub-t">探查对象<span class="muted sub-t-cnt">已选 {{ selectedTables.length }} 张</span></div>
          <el-select v-model="form.source_ds_id" placeholder="选择源数据源" size="small" style="width:100%;margin-bottom:8px" @change="loadTables">
            <el-option v-for="d in dsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
          <el-input v-if="sourceTables.length" v-model="tableKw" size="small" placeholder="表名模糊检索" clearable style="margin-bottom:6px">
            <template #prepend>
              <el-checkbox :model-value="allFilteredChecked" :indeterminate="someFilteredChecked" @change="toggleAll($event)">全选</el-checkbox>
            </template>
          </el-input>
          <div class="table-list">
            <div v-for="t in filteredTables" :key="t.full" class="table-row" :class="{ on: selectedTables.includes(t.full), disabled: !!existsMap[t.full] }">
              <el-checkbox :model-value="selectedTables.includes(t.full)" :disabled="!!existsMap[t.full]" @change="(v:any) => toggleTable(t.full, v)">
                <el-icon v-if="t.is_view"><Document /></el-icon> {{ t.name }}
                <span v-if="existsMap[t.full]" class="muted">（目标库已存在）</span>
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
            <el-form-item label="任务名称"><el-input v-model="form.name" placeholder="探查任务名" /></el-form-item>
            <el-form-item label="所属层级">
              <el-select v-model="form.target_db" style="width:100%" @change="checkExists">
                <el-option v-for="l in layers" :key="l.code" :label="`${l.code.toUpperCase()} 层（${l.name || l.code}）`" :value="l.code" />
              </el-select>
              <div v-if="layerBindingHint" class="muted layer-hint"><el-icon><Link /></el-icon> {{ layerBindingHint }}</div>
            </el-form-item>
            <el-form-item label="首次建表"><el-switch v-model="form.first_create_table" /><span class="muted" style="margin-left:8px">目标库已存在同名表则只记录版本不建表</span></el-form-item>
            <el-form-item label="结构告警"><el-switch v-model="form.alert_enabled" /><span class="muted" style="margin-left:8px">表结构变化时审计/预警</span></el-form-item>
            <el-form-item label="数据元">
              <el-select v-model="selectedElements" multiple filterable collapse-tags collapse-tags-tooltip style="width:100%" placeholder="选择数据标准中的数据元（首次建表追加列）">
                <el-option v-for="e in elements" :key="e.name" :label="`${e.name} (${e.data_type || ''})`" :value="e.name" />
              </el-select>
            </el-form-item>
            <el-form-item label="任务控制">
              <el-input v-model="form.cron" placeholder="周期秒数，如 60（上线后按此间隔周期执行）">
                <template #append>秒</template>
              </el-input>
            </el-form-item>
          </el-form>
        </div>
      </div>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-drawer>

    <!-- 字段配置（C 类小弹窗保留） -->
    <el-dialog v-model="colDlg" :title="`字段探查配置 - ${curTable}`" width="680px" append-to-body>
      <el-table :data="curCols" size="small" border v-loading="colLoading" max-height="380">
        <el-table-column prop="name" label="字段" min-width="140" />
        <el-table-column prop="type" label="类型" width="120" />
        <el-table-column label="唯一值" width="70"><template #default="{ row }"><el-checkbox v-model="colOpts[row.name].unique" /></template></el-table-column>
        <el-table-column label="空值" width="60"><template #default="{ row }"><el-checkbox v-model="colOpts[row.name].null" /></template></el-table-column>
        <el-table-column label="取值分布" width="100">
          <template #default="{ row }">
            <el-checkbox v-model="colOpts[row.name].dist" :disabled="!isNumeric(row.type)" />
            <span v-if="!isNumeric(row.type)" class="muted">需数值列</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer><el-button @click="colDlg = false">取消</el-button><el-button type="primary" @click="saveColCfg">确定</el-button></template>
    </el-dialog>

    <!-- 日志（右抽屉：左执行列表 + 右滚屏日志） -->
    <el-drawer v-model="runsDlg" :title="`探查日志 - ${current?.name || ''}`" size="1100px" destroy-on-close>
      <div class="dl-toolbar" style="padding:0 0 10px;margin-bottom:10px">
        <span class="muted">清洗规则：</span>
        <el-select v-model="clearRule" size="small" style="width:160px">
          <el-option label="清除全部日志" value="all" />
          <el-option label="仅清除失败日志" value="failed" />
          <el-option label="清除 7 天前日志" value="before7d" />
        </el-select>
        <el-button size="small" type="danger" plain :loading="clearing" @click="clearRuns">清除日志</el-button>
        <div class="toolbar-actions"><span class="muted">共 {{ runRows.length }} 次执行</span></div>
      </div>
      <div class="run-layout">
        <!-- 左：执行列表 -->
        <div class="run-list-pane">
          <div v-for="r in runRows" :key="r.id" class="run-item" :class="{ on: curRun?.id === r.id }" @click="pickRun(r)">
            <div class="ri-line1">
              <el-tag size="small" :type="runTagType(r.status)">{{ runTagLabel(r.status) }}</el-tag>
              <span class="ri-time">{{ fmtShort(r.start_time) }}</span>
            </div>
            <div class="ri-line2 muted">
              变 <b :class="{ chg: (r.tables_changed || 0) > 0 }">{{ r.tables_changed }}</b> / 共 {{ r.tables_total }} 张
              <span class="ri-by">· {{ r.triggered_by || '系统' }}</span>
            </div>
          </div>
          <div v-if="!runRows.length" class="muted empty-tip">暂无执行记录，点击列表「执行」触发一次探查</div>
        </div>
        <!-- 右：选中执行详情 + 滚屏日志 -->
        <div class="run-log-pane">
          <template v-if="curRun">
            <div class="run-meta">
              <div class="rm-item"><span class="muted">开始</span><b>{{ curRun.start_time?.replace('T', ' ') }}</b></div>
              <div class="rm-item"><span class="muted">结束</span><b>{{ curRun.end_time?.replace('T', ' ') || '—' }}</b></div>
              <div class="rm-item"><span class="muted">变化</span><b :class="{ chg: (curRun.tables_changed || 0) > 0 }">{{ curRun.tables_changed }} / {{ curRun.tables_total }} 张</b></div>
              <div class="rm-act">
                <el-button size="small" @click="openRecords(current, curRun)">探查结果</el-button>
                <el-button size="small" @click="copyLog"><el-icon><CopyDocument /></el-icon> 复制日志</el-button>
              </div>
            </div>
            <el-alert v-if="curRun.error_msg" type="error" :closable="false" show-icon style="margin-bottom:8px">{{ curRun.error_msg }}</el-alert>
            <LogStream :log="curLog" height="520px" />
          </template>
          <div v-else class="muted empty-tip">左侧选择一次执行查看日志</div>
        </div>
      </div>
    </el-drawer>

    <!-- 记录 + 版本对比（右抽屉） -->
    <el-drawer v-model="recDlg" :title="`探查记录 - ${current?.name || ''}`" size="980px" destroy-on-close>
      <div class="rec-layout">
        <!-- 左：表列表 -->
        <div class="rec-tables-pane">
          <div v-for="t in recEntries" :key="t.name" class="rec-t-item" :class="{ on: recTable === t.name }" @click="pickRecTable(t.name)">
            <el-icon class="rt-ic"><Document /></el-icon>
            <span class="rt-name" :title="t.name">{{ t.name }}</span>
            <el-tag size="small" type="info" effect="plain">v{{ t.versions.length }}</el-tag>
          </div>
          <div v-if="!recEntries.length" class="muted empty-tip">暂无探查记录</div>
        </div>
        <!-- 右：版本时间线 + 字段对比 -->
        <div class="rec-main-pane">
          <template v-if="recTable">
            <div class="rec-cmp-bar">
              <span class="muted">版本对比：</span>
              <el-select v-model="cmpV1" size="small" style="width:96px" @change="loadCompare"><el-option v-for="v in recVersions" :key="v" :label="'v'+v" :value="v" /></el-select>
              <span class="muted">→</span>
              <el-select v-model="cmpV2" size="small" style="width:96px" @change="loadCompare"><el-option v-for="v in recVersions" :key="v" :label="'v'+v" :value="v" /></el-select>
              <span class="muted">共 {{ cmpCols.length }} 个字段</span>
            </div>
            <div v-if="recVersionRows.length" class="rec-timeline">
              <span v-for="s in recVersionRows" :key="s.version_n" class="rec-ver-chip" :class="{ cur: isCmpVersion(s.version_n) }">
                <b>v{{ s.version_n }}</b> {{ fmtShort(s.created_time) }}
              </span>
            </div>
            <el-table v-if="cmpCols.length" :data="cmpCols" size="small" border max-height="430">
              <el-table-column prop="name" label="字段" min-width="150" />
              <el-table-column width="150">
                <template #header>v{{ cmpV1 ?? '?' }} 类型</template>
                <template #default="{ row }"><span :class="{ rm: row.r1 }">{{ row.t1 }}</span></template>
              </el-table-column>
              <el-table-column width="150">
                <template #header>v{{ cmpV2 ?? '?' }} 类型</template>
                <template #default="{ row }"><span :class="{ add: row.r2, chg: row.chg }">{{ row.t2 }}</span></template>
              </el-table-column>
              <el-table-column label="变化" width="80" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.r2" size="small" type="success">新增</el-tag>
                  <el-tag v-else-if="row.r1" size="small" type="danger">删除</el-tag>
                  <el-tag v-else-if="row.chg" size="small" type="warning">变更</el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
            </el-table>
            <div v-else class="muted empty-tip">该表不足两个版本，暂无对比；执行探查产生新版本后可对比</div>
          </template>
          <div v-else class="muted empty-tip">左侧选择探查表查看版本与字段对比</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled, Document, DataAnalysis, CopyDocument, Link } from '@element-plus/icons-vue'
import { api, errMsg, type DataSourceRow, type ProfileJobRow } from '@/api'
import LogStream from '@/components/LogStream.vue'

const jobs = ref<ProfileJobRow[]>([])
const dsList = ref<DataSourceRow[]>([])
const elements = ref<any[]>([])
// 数仓分层（动态）+ 层→数据源绑定提示
const layers = ref<any[]>([])
const layerBinds = ref<any[]>([])
const layerBindingHint = computed(() => {
  const code = form.target_db
  if (!code) return ''
  const binds = layerBinds.value.filter((b: any) => (b.layer_code || '').toLowerCase() === String(code).toLowerCase())
  if (!binds.length) return `该层未绑定数据源 → 建表写入主库 StarRocks`
  const txt = binds.map((b: any) => `${b.ds_name}（${b.ds_type}）`).join('、')
  return `该层绑定：${txt} → 首次建表将按绑定写入该目标`
})
const loading = ref(false)
const runningId = ref<number | null>(null)
const current = ref<ProfileJobRow | null>(null)

const dsMap = computed<Record<number, string>>(() => {
  const m: Record<number, string> = {}
  dsList.value.forEach(d => { m[d.id] = `${d.name}(${d.type})` })
  return m
})

// 统计条
const statCron = computed(() => jobs.value.filter(j => j.cron).length)
const statOnline = computed(() => jobs.value.filter(j => j.status === 'ONLINE').length)
const statTables = computed(() => jobs.value.reduce((s, j) => s + (j.table_count || 0), 0))
const statDs = computed(() => new Set(jobs.value.map(j => j.source_ds_id)).size)
const statLastOk = computed(() => jobs.value.filter(j => j.last_status === 'SUCCESS').length)
const statLastFail = computed(() => jobs.value.filter(j => j.last_run_time && j.last_status && j.last_status !== 'SUCCESS').length)
const statNever = computed(() => jobs.value.filter(j => !j.last_run_time).length)

// 执行状态 tag：PARTIAL=部分表失败（黄），FAIL=失败（红）
function runTagType(s?: string) { return s === 'SUCCESS' ? 'success' : s === 'PARTIAL' ? 'warning' : s ? 'danger' : 'info' }
function runTagLabel(s?: string) { return s === 'SUCCESS' ? '成功' : s === 'PARTIAL' ? '部分失败' : s === 'FAIL' ? '失败' : (s || '—') }

// ISO 时间 → 短格式（MM-DD HH:mm）
function fmtShort(s?: string) {
  if (!s) return '—'
  const t = s.replace('T', ' ')
  return t.length >= 16 ? t.slice(5, 16) : t
}

// 检索 + 分页（客户端）
const kw = ref(''); const kwCreator = ref(''); const kwStatus = ref('')
const page = reactive({ page: 1, size: 10 })
const filtered = computed(() => jobs.value.filter(j =>
  (!kw.value || (j.name || '').toLowerCase().includes(kw.value.toLowerCase())) &&
  (!kwCreator.value || (j.create_by || '').toLowerCase().includes(kwCreator.value.toLowerCase())) &&
  (!kwStatus.value || j.status === kwStatus.value)))
const paged = computed(() => filtered.value.slice((page.page - 1) * page.size, page.page * page.size))
function resetQuery() { kw.value = ''; kwCreator.value = ''; kwStatus.value = ''; page.page = 1 }
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }

const dlg = ref(false); const saving = ref(false)
const form = reactive<any>({ id: null, name: '', source_ds_id: null, target_db: 'dwd', first_create_table: true, alert_enabled: true, extra_columns: '', cron: '', status: 'OFFLINE' })

const sourceTables = ref<any[]>([])
const tableKw = ref('')
const selectedTables = ref<string[]>([])
const tableCfgs = reactive<Record<string, Record<string, any>>>({})
const existsMap = reactive<Record<string, boolean>>({})
const filteredTables = computed(() => {
  if (!tableKw.value) return sourceTables.value
  const k = tableKw.value.toLowerCase()
  return sourceTables.value.filter((t: any) => t.name.toLowerCase().includes(k))
})
const selectableFiltered = computed(() => filteredTables.value.filter((t: any) => !existsMap[t.full]))
const allFilteredChecked = computed(() => selectableFiltered.value.length > 0 && selectableFiltered.value.every((t: any) => selectedTables.value.includes(t.full)))
const someFilteredChecked = computed(() => selectableFiltered.value.some((t: any) => selectedTables.value.includes(t.full)) && !allFilteredChecked.value)
function toggleAll(v: any) {
  const fs = filteredTables.value.map((t: any) => t.full)
  if (v) {
    fs.forEach(f => { if (!existsMap[f] && !selectedTables.value.includes(f)) { selectedTables.value.push(f); tableCfgs[f] = tableCfgs[f] || {} } })
  } else {
    selectedTables.value = selectedTables.value.filter(x => !fs.includes(x))
    fs.forEach(f => delete tableCfgs[f])
  }
}

const selectedElements = ref<string[]>([])

const colDlg = ref(false); const colLoading = ref(false); const curTable = ref(''); const curCols = ref<any[]>([]); const colOpts = reactive<Record<string, any>>({})

// 日志抽屉：左列表右 LogStream
const runsDlg = ref(false)
const runRows = ref<any[]>([])
const curRun = ref<any>(null)
const curLog = ref('')
const clearing = ref(false); const clearRule = ref('all')

// 记录抽屉
const recDlg = ref(false)
const recEntries = ref<{ name: string; versions: number[] }[]>([])
const recVersionRows = ref<any[]>([])
const recTable = ref(''); const recVersions = ref<number[]>([])
const cmpV1 = ref<number | null>(null); const cmpV2 = ref<number | null>(null); const cmpCols = ref<any[]>([])

function isNumeric(t: string) { return /(int|long|bigint|double|decimal|float|numeric|real)/i.test(t || '') }

async function load() {
  loading.value = true
  try {
    const [j, d, e, ls, bs] = await Promise.all([api.daProfileJobs(), api.daSources(), api.govElements(), api.govLayers(), api.govLayerDs().catch(() => [])])
    jobs.value = j; dsList.value = d; elements.value = (e || [])
    layers.value = (ls || []).filter((l: any) => l.status !== 'DISABLED')
    layerBinds.value = bs || []
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}

async function loadTables(dsId: number) {
  selectedTables.value = []
  Object.keys(tableCfgs).forEach(k => delete tableCfgs[k])
  sourceTables.value = []
  Object.keys(existsMap).forEach(k => delete existsMap[k])
  if (!dsId) return
  try {
    const list = await api.daProfileTables(dsId)
    sourceTables.value = (list || []).map((t: any) => ({ name: t.name, full: (t.schema_name ? t.schema_name + '.' : '') + t.name, is_view: !!t.is_view }))
    await checkExists()
  } catch (e: any) { ElMessage.error(errMsg(e, '加载源表失败')) }
}

async function checkExists() {
  Object.keys(existsMap).forEach(k => delete existsMap[k])
  if (!form.target_db || !sourceTables.value.length) return
  try {
    const names = sourceTables.value.map((t: any) => t.full).join(',')
    const m = await api.daProfileTargetExistsBatch(form.target_db, names)
    Object.keys(m).forEach(k => { if (m[k]) existsMap[k] = true })
    // 已置灰的从选中移除
    selectedTables.value = selectedTables.value.filter(f => !existsMap[f])
  } catch { /* ignore */ }
}

function toggleTable(full: string, v: any) {
  if (existsMap[full]) return
  if (v) { selectedTables.value.push(full); tableCfgs[full] = tableCfgs[full] || {} }
  else { selectedTables.value = selectedTables.value.filter(x => x !== full); delete tableCfgs[full] }
}

async function openColCfg(t: any) {
  curTable.value = t.full; colDlg.value = true; colLoading.value = true
  try {
    curCols.value = await api.daProfileColumns(form.source_ds_id, t.full)
    Object.keys(colOpts).forEach(k => delete colOpts[k])
    const exist = tableCfgs[t.full] || {}
    curCols.value.forEach((c: any) => {
      const e = exist[c.name] || {}
      colOpts[c.name] = { unique: !!e.unique, null: !!e.null, dist: !!e.dist }
    })
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { colLoading.value = false }
}
function saveColCfg() {
  const cfg: Record<string, any> = {}
  curCols.value.forEach((c: any) => {
    const o = colOpts[c.name]; const opts: string[] = []
    if (o.unique) opts.push('unique')
    if (o.null) opts.push('null')
    if (o.dist && isNumeric(c.type)) opts.push('dist')
    if (opts.length) cfg[c.name] = opts
  })
  tableCfgs[curTable.value] = cfg; colDlg.value = false; ElMessage.success('字段配置已保存')
}

function open(row?: ProfileJobRow) {
  Object.assign(form, { id: null, name: '', source_ds_id: null, target_db: 'dwd', first_create_table: true, alert_enabled: true, extra_columns: '', cron: '', status: 'OFFLINE' })
  selectedTables.value = []; selectedElements.value = []
  Object.keys(tableCfgs).forEach(k => delete tableCfgs[k]); Object.keys(existsMap).forEach(k => delete existsMap[k])
  sourceTables.value = []
  if (row) {
    Object.assign(form, { id: row.id, name: row.name, source_ds_id: row.source_ds_id, target_db: row.target_db, first_create_table: row.first_create_table, alert_enabled: row.alert_enabled, extra_columns: row.extra_columns || '', cron: row.cron || '', status: row.status })
    try { selectedElements.value = (JSON.parse(row.extra_columns || '[]')).map((x: any) => x.name) } catch { selectedElements.value = [] }
    loadTables(row.source_ds_id).then(() => loadJobDetail(row.id))
  }
  dlg.value = true
}
async function loadJobDetail(id: number) {
  try {
    const d = await api.daProfileJobDetail(id)
    ;(d.tables || []).forEach((t: any) => {
      const full = t.table_name
      if (!existsMap[full]) selectedTables.value.push(full)
      try { tableCfgs[full] = JSON.parse(t.columns_config || '{}') } catch { tableCfgs[full] = {} }
    })
  } catch { /* ignore */ }
}

async function save() {
  if (!form.name || !form.source_ds_id) return ElMessage.warning('请填名称与源数据源')
  if (!selectedTables.value.length) return ElMessage.warning('请至少勾选一张表')
  // 数据元 → 追加列：name=中文标准名（展示），col=英文名（建表列名，回退 code 小写），type=标准类型
  const elemMap: Record<string, any> = {}
  elements.value.forEach((e: any) => {
    elemMap[e.name] = { type: e.data_type || 'VARCHAR', col: e.en_name || String(e.code || '').toLowerCase() || e.name }
  })
  form.extra_columns = JSON.stringify(selectedElements.value.map((n: string) => ({ name: n, col: elemMap[n]?.col || n, type: elemMap[n]?.type || 'VARCHAR' })))
  const tables = selectedTables.value.map(t => ({ table_name: t, is_view: false, columns_config: JSON.stringify(tableCfgs[t] || {}) }))
  saving.value = true
  try { await api.daSaveProfileJob({ ...form, tables }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: ProfileJobRow) {
  try { await ElMessageBox.confirm(`确定删除探查任务 ${row.name}？`, '提示', { type: 'warning' }) } catch { return }
  try { await api.daDeleteProfileJob(row.id); ElMessage.success('已删除'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function run(row: ProfileJobRow) {
  runningId.value = row.id
  try { const r: any = await api.daProfileRun(row.id); ElMessage.success(`执行完成：变化 ${r.tablesChanged}/${r.tablesTotal}`); await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { runningId.value = null }
}
async function online(row: ProfileJobRow) { try { await api.daProfileOnline(row.id); ElMessage.success('已上线'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function offline(row: ProfileJobRow) { try { await api.daProfileOffline(row.id); ElMessage.success('已下线'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) } }

async function openRuns(row: ProfileJobRow) {
  current.value = row; runsDlg.value = true
  curRun.value = null; curLog.value = ''
  try {
    runRows.value = await api.daProfileRuns(row.id)
    if (runRows.value.length) await pickRun(runRows.value[0])   // 默认选中最近一次
  } catch { runRows.value = [] }
}

async function pickRun(r: any) {
  curRun.value = r; curLog.value = ''
  try { const d = await api.daProfileRunDetail(r.id); curLog.value = d.log_text || '(无日志)' }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

async function clearRuns() {
  if (!current.value) return
  try { await ElMessageBox.confirm(`按规则【${clearRule.value === 'all' ? '清除全部' : clearRule.value === 'failed' ? '仅失败' : '7天前'}】清除日志？`, '提示', { type: 'warning' }) } catch { return }
  clearing.value = true
  try {
    const r: any = await api.daProfileClearRuns(current.value.id, clearRule.value)
    ElMessage.success(`已清除 ${r.deleted} 条`)
    runRows.value = await api.daProfileRuns(current.value.id)
    curRun.value = null; curLog.value = ''
    if (runRows.value.length) await pickRun(runRows.value[0])
  }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { clearing.value = false }
}
async function copyLog() { try { await navigator.clipboard.writeText(curLog.value); ElMessage.success('已复制') } catch { ElMessage.warning('复制失败，请手动选择') } }

async function openRecords(row: ProfileJobRow | null, runRow?: any) {
  if (!row) return
  current.value = row; recDlg.value = true
  cmpCols.value = []; recTable.value = ''; cmpV1.value = null; cmpV2.value = null
  recEntries.value = []; recVersionRows.value = []
  try {
    const list = await api.daProfileRecords(row.id)
    const map = new Map<string, number[]>()
    ;(list || []).forEach((s: any) => { const arr = map.get(s.table_name) || []; arr.push(s.version_n); map.set(s.table_name, arr) })
    recEntries.value = [...map.entries()].map(([name, versions]) => ({ name, versions: [...new Set(versions)].sort((a, b) => b - a) }))
    if (recEntries.value.length) pickRecTable(recEntries.value[0].name)
  } catch { recEntries.value = [] }
  if (runRow) ElMessage.info(runRow.tables_changed > 0 ? `本次变化 ${runRow.tables_changed} 张表` : '本次无表结构变化')
}

function pickRecTable(name: string) {
  recTable.value = name
  const ent = recEntries.value.find(e => e.name === name)
  const vers = ent ? ent.versions : []
  recVersions.value = vers
  // 版本时间线（该表全部快照记录）
  recVersionRows.value = []
  cmpV1.value = vers.length >= 2 ? vers[vers.length - 1] : null
  cmpV2.value = vers.length >= 2 ? vers[0] : null
  loadCompare()
}

async function loadCompare() {
  cmpCols.value = []
  if (!recTable.value || !cmpV1.value || !cmpV2.value || !current.value) return
  try {
    const r = await api.daProfileCompare(current.value.id, recTable.value, cmpV1.value, cmpV2.value)
    const m1 = parseCols(r.v1?.columns_json), m2 = parseCols(r.v2?.columns_json)
    const names = new Set([...Object.keys(m1), ...Object.keys(m2)])
    cmpCols.value = [...names].map(n => {
      const t1 = m1[n], t2 = m2[n]
      return { name: n, t1: t1 || '—', t2: t2 || '—', r1: !t1, r2: !t2, chg: !!(t1 && t2 && t1 !== t2) }
    })
    // 时间线需要快照时间：后端 record/list 返回 created_time，这里随 compare 一起重拉该表版本行
    const list = await api.daProfileRecords(current.value.id)
    recVersionRows.value = (list || []).filter((s: any) => s.table_name === recTable.value)
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}

function isCmpVersion(v: number) { return v === cmpV1.value || v === cmpV2.value }
function parseCols(json: any): Record<string, string> {
  const out: Record<string, string> = {}
  try { JSON.parse(json || '[]').forEach((c: any) => { out[c.name] = c.type }) } catch {}
  return out
}

onMounted(load)
</script>

<style scoped>
.stat-bar { margin-bottom: 12px; }
.stat-card { border: 1px solid var(--tech-panel-border); border-radius: 6px; padding: 10px 14px; }
.stat-head { display: flex; align-items: baseline; gap: 8px; }
.stat-head b { font-size: 22px; }
.stat-head b.ok { color: var(--tech-success); }
.stat-head b .danger, .stat-sub .danger { color: var(--tech-danger); }
.stat-sub { margin-top: 4px; }
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.ct-left { display: inline-flex; align-items: center; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.hint b { color: var(--tech-primary); }

/* 列表「最近执行」列 */
.last-run-line { display: flex; align-items: center; gap: 6px; }
.lr-time { font-size: 12px; }
.lr-sub { margin-top: 2px; }
.chg { color: var(--tech-warn); font-weight: 600; }

/* 编辑抽屉 */
.edit-grid { display: grid; grid-template-columns: 1.15fr 1fr; gap: 16px; }
.pane { border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 12px; }
.sub-t { font-weight: 600; margin-bottom: 10px; color: var(--tech-text); display: flex; align-items: baseline; justify-content: space-between; }
.sub-t-cnt { font-weight: 400; }
.table-list { max-height: 460px; overflow-y: auto; }
.layer-hint { display: flex; align-items: center; gap: 5px; margin-top: 4px; line-height: 1.5; }
.table-row { display: flex; align-items: center; justify-content: space-between; padding: 4px 6px; border-radius: 4px; }
.table-row.on { background: color-mix(in srgb, var(--tech-primary) 10%, transparent); }
.table-row.disabled { opacity: 0.5; }

/* 日志抽屉：左列表右滚屏 */
.run-layout { display: flex; gap: 14px; }
.run-list-pane { width: 300px; flex-shrink: 0; border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 6px; max-height: 640px; overflow-y: auto; }
.run-item { padding: 8px 10px; border-radius: 6px; cursor: pointer; border: 1px solid transparent; }
.run-item:hover { background: color-mix(in srgb, var(--tech-primary) 6%, transparent); }
.run-item.on { background: color-mix(in srgb, var(--tech-primary) 10%, transparent); border-color: color-mix(in srgb, var(--tech-primary) 35%, transparent); }
.ri-line1 { display: flex; align-items: center; gap: 8px; }
.ri-time { font-size: 12.5px; font-weight: 600; }
.ri-line2 { margin-top: 3px; }
.ri-by { margin-left: auto; }
.run-log-pane { flex: 1; min-width: 0; }
.run-meta { display: flex; align-items: center; gap: 18px; margin-bottom: 10px; flex-wrap: wrap; }
.rm-item { display: flex; align-items: baseline; gap: 6px; font-size: 13px; }
.rm-act { margin-left: auto; display: flex; gap: 8px; }

/* 记录抽屉：左表列表右对比 */
.rec-layout { display: flex; gap: 14px; }
.rec-tables-pane { width: 280px; flex-shrink: 0; border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 6px; max-height: 640px; overflow-y: auto; }
.rec-t-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 6px; cursor: pointer; border: 1px solid transparent; }
.rec-t-item:hover { background: color-mix(in srgb, var(--tech-primary) 6%, transparent); }
.rec-t-item.on { background: color-mix(in srgb, var(--tech-primary) 10%, transparent); border-color: color-mix(in srgb, var(--tech-primary) 35%, transparent); }
.rt-ic { color: var(--tech-primary); flex-shrink: 0; }
.rt-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.rec-main-pane { flex: 1; min-width: 0; }
.rec-cmp-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.rec-timeline { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.rec-ver-chip { font-size: 12px; color: var(--tech-text-muted); border: 1px dashed var(--tech-panel-border); border-radius: 12px; padding: 2px 10px; }
.rec-ver-chip b { color: var(--tech-text); margin-right: 4px; }
.rec-ver-chip.cur { color: var(--tech-primary); border-color: color-mix(in srgb, var(--tech-primary) 45%, transparent); }
.rec-ver-chip.cur b { color: var(--tech-primary); }
.empty-tip { padding: 24px 8px; text-align: center; }

.add { color: var(--tech-success); font-weight: 600; }
.rm { color: var(--tech-danger); text-decoration: line-through; }
</style>
