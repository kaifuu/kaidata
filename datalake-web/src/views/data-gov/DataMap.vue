<template>
  <div class="dl-card">
    <div class="card-title"><span>数据地图</span><span class="role-tag">系统管理员</span></div>
    <div class="hint" style="margin-bottom:8px">企业数据目录：检索 / 类目 / 资产详情（业务信息·血缘·版本）。对标阿里 DataWorks 数据地图、百度 EasyDAP。</div>

    <!-- 顶部全局检索 -->
    <div class="search-bar">
      <el-input v-model="kw" placeholder="搜索 表/字段/接口/文件（中英文名、描述）" size="small" clearable style="width:440px" @keyup.enter="doSearch">
        <template #append><el-button @click="doSearch">检索</el-button></template>
      </el-input>
    </div>

    <el-row :gutter="12" style="margin-top:10px">
      <el-col :span="5">
        <el-radio-group v-model="assetType" size="small" style="margin-bottom:10px" @change="onType">
          <el-radio-button label="table">库表</el-radio-button>
          <el-radio-button label="api">接口</el-radio-button>
          <el-radio-button label="file">文件</el-radio-button>
        </el-radio-group>
        <div v-if="assetType === 'table'">
          <div class="hint" style="margin-bottom:4px">资产类目</div>
          <el-tree :data="catalog" :props="{ label: 'name' }" node-key="id" highlight-current :expand-on-click-node="false" default-expand-all />
        </div>
        <div v-else class="hint">按类型展示全部{{ assetType === 'api' ? '接口' : '文件' }}资产。</div>
      </el-col>
      <el-col :span="19">
        <el-table :data="pagedRows" size="small" stripe border v-loading="loading" highlight-current-row @row-click="openDetail">
          <template v-if="assetType === 'table'">
            <el-table-column prop="table_name" label="表名" min-width="170" />
            <el-table-column prop="cn_name" label="中文名" min-width="120" />
            <el-table-column prop="layer_code" label="层级" width="80" />
            <el-table-column label="填充度" width="80"><template #default="{ row }">{{ row.fill_percent || 0 }}%</template></el-table-column>
            <el-table-column prop="mount_status" label="挂载" width="70" />
          </template>
          <template v-else-if="assetType === 'api'">
            <el-table-column prop="service_name" label="服务" min-width="170" />
            <el-table-column prop="code" label="编码" width="120" />
            <el-table-column prop="method" label="方法" width="70" />
            <el-table-column label="填充度" width="80"><template #default="{ row }">{{ row.fill_percent || 0 }}%</template></el-table-column>
          </template>
          <template v-else>
            <el-table-column label="名称" min-width="170"><template #default="{ row }">{{ row.cn_name || row.path }}</template></el-table-column>
            <el-table-column prop="store_name" label="存储源" width="100" />
            <el-table-column prop="file_format" label="格式" width="80" />
            <el-table-column label="填充度" width="80"><template #default="{ row }">{{ row.fill_percent || 0 }}%</template></el-table-column>
          </template>
        </el-table>
        <el-pagination style="margin-top:10px;justify-content:flex-end"
          :current-page="page" :page-size="pageSize" :page-sizes="[20, 50, 100, 200]"
          :total="rows.length" size="small" background
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="v => (page = v)"
          @size-change="v => { pageSize = v; page = 1 }" />
      </el-col>
    </el-row>

    <!-- 检索结果 -->
    <el-dialog v-model="showResult" title="检索结果" width="780px">
      <div class="sec">库表（{{ (searchResult.tables || []).length }}）</div>
      <el-table :data="searchResult.tables || []" size="small" border max-height="120">
        <el-table-column label="表名" min-width="150"><template #default="{ row }"><el-link type="primary" @click="goto('table', row.id)">{{ row.table_name }}</el-link></template></el-table-column>
        <el-table-column prop="cn_name" label="中文名" min-width="120" />
        <el-table-column prop="schema_name" label="Schema" width="100" />
      </el-table>
      <div class="sec">字段（{{ (searchResult.fields || []).length }}）</div>
      <el-table :data="searchResult.fields || []" size="small" border max-height="100">
        <el-table-column prop="table" label="表" min-width="140" /><el-table-column prop="field" label="字段" width="130" /><el-table-column prop="comment" label="注释" />
      </el-table>
      <div class="sec">接口（{{ (searchResult.apis || []).length }}）· 文件（{{ (searchResult.files || []).length }}）</div>
      <el-table :data="[...(searchResult.apis || []), ...(searchResult.files || [])]" size="small" border max-height="100">
        <el-table-column label="名称" min-width="150"><template #default="{ row }"><el-link type="primary" @click="goto(row.service_id ? 'api' : 'file', row.service_id || row.id)">{{ row.name || row.cn_name }}</el-link></template></el-table-column>
        <el-table-column label="编码/路径" min-width="150"><template #default="{ row }">{{ row.code || row.path }}</template></el-table-column>
      </el-table>
    </el-dialog>

    <!-- 详情 drawer -->
    <el-drawer v-model="detailDlg" :title="detailTitle" size="82%">
      <el-tabs v-model="detailTab">
        <!-- 概览 -->
        <el-tab-pane label="概览" name="overview">
          <template v-if="assetType === 'table'">
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="表名">{{ detail.table_name }}</el-descriptions-item>
              <el-descriptions-item label="Schema">{{ detail.schema_name }}</el-descriptions-item>
              <el-descriptions-item label="行数">{{ detail.row_count }}</el-descriptions-item>
              <el-descriptions-item label="同步时间">{{ detail.synced_time }}</el-descriptions-item>
              <el-descriptions-item label="当前版本">v{{ detail.current_version || 0 }}</el-descriptions-item>
              <el-descriptions-item label="挂载状态">{{ detail.mount_status || 'NONE' }}</el-descriptions-item>
            </el-descriptions>
            <div class="hint" style="margin:10px 0 4px">字段（{{ cols.length }}）</div>
            <el-table :data="cols" size="small" border max-height="300">
              <el-table-column prop="name" label="字段" min-width="140" /><el-table-column prop="type" label="类型" width="150" /><el-table-column prop="comment" label="注释" show-overflow-tooltip />
            </el-table>
          </template>
          <template v-else-if="assetType === 'api'">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="服务名">{{ svc.name }}</el-descriptions-item>
              <el-descriptions-item label="编码">{{ svc.code }}</el-descriptions-item>
              <el-descriptions-item label="方法">{{ svc.method }}</el-descriptions-item>
              <el-descriptions-item label="路径">{{ svc.path }}</el-descriptions-item>
            </el-descriptions>
            <div class="hint" style="margin:10px 0 4px">SQL</div>
            <el-input type="textarea" :rows="6" readonly :model-value="svc.sql_text" />
            <div class="hint" style="margin:8px 0 4px">参数</div>
            <el-input type="textarea" :rows="3" readonly :model-value="svc.params" />
          </template>
          <template v-else>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="文件名">{{ detail.cn_name }}</el-descriptions-item>
              <el-descriptions-item label="路径">{{ detail.path }}</el-descriptions-item>
              <el-descriptions-item label="存储源">{{ detail.store_name }}</el-descriptions-item>
              <el-descriptions-item label="格式">{{ detail.file_format }}</el-descriptions-item>
            </el-descriptions>
            <div style="margin:10px 0 4px"><span class="hint">文件 Schema</span> <el-button size="small" @click="addCol">新增字段</el-button></div>
            <el-table :data="fileCols" size="small" border>
              <el-table-column label="字段名" min-width="160"><template #default="{ row }"><el-input v-model="row.name" size="small" /></template></el-table-column>
              <el-table-column label="类型" width="150"><template #default="{ row }"><el-input v-model="row.type" size="small" /></template></el-table-column>
              <el-table-column label="操作" width="60"><template #default="{ $index }"><el-button link size="small" type="danger" @click="fileCols.splice($index, 1)">删</el-button></template></el-table-column>
            </el-table>
          </template>
        </el-tab-pane>

        <!-- 业务信息（轻量，去必填） -->
        <el-tab-pane label="业务信息" name="business">
          <el-form :model="biz" label-width="100px" size="small">
            <el-form-item label="中文名"><el-input v-model="biz.cn_name" /></el-form-item>
            <el-form-item label="所属部门"><el-input v-model="biz.dept" /></el-form-item>
            <el-form-item label="应用系统"><el-input v-model="biz.app_system" /></el-form-item>
            <el-form-item label="资源管理员"><el-input v-model="biz.admin_owner" /></el-form-item>
            <el-form-item label="联系方式"><el-input v-model="biz.admin_contact" /></el-form-item>
            <el-form-item label="数据分类"><el-input v-model="biz.data_category" /></el-form-item>
            <el-form-item label="安全级别"><el-select v-model="biz.security_level" clearable style="width:100%"><el-option v-for="s in standards" :key="s.code" :label="s.name" :value="s.code" /></el-select></el-form-item>
            <el-form-item v-if="assetType === 'table'" label="所属主题"><el-select v-model="biz.subject_id" clearable style="width:100%"><el-option v-for="s in subjectOpts" :key="s.id" :label="s.name" :value="s.id" /></el-select></el-form-item>
            <el-form-item label="业务描述"><el-input v-model="biz.description" type="textarea" :rows="2" /></el-form-item>
          </el-form>
          <el-button type="primary" :loading="saving" @click="saveBiz">保存</el-button>
          <span class="hint" style="margin-left:10px">填充度 {{ biz.fill_percent || 0 }}%（业务信息无强制必填，按需补录）</span>
        </el-tab-pane>

        <!-- 血缘（仅库表） -->
        <el-tab-pane v-if="assetType === 'table'" label="血缘" name="lineage">
          <div style="margin-bottom:6px">
            <el-radio-group v-model="lineageMode" size="small" @change="drawLineage">
              <el-radio-button label="lineage">上游血缘</el-radio-button>
              <el-radio-button label="impact">下游影响</el-radio-button>
            </el-radio-group>
          </div>
          <v-chart v-if="graphData" :option="graphOption" :theme="theme" autoresize class="chart" />
          <div v-else class="empty">切换上游/下游查看血缘（基于离线接入 / 实时开发 / 数据接出任务）</div>
        </el-tab-pane>

        <!-- 质量（仅库表） -->
        <el-tab-pane v-if="assetType === 'table'" label="质量" name="quality">
          <div class="empty">质量检测按「数据质量」规则维度执行，按表聚合视图留待后续对接 gov_quality_result。</div>
        </el-tab-pane>

        <!-- 产出任务（仅库表） -->
        <el-tab-pane v-if="assetType === 'table'" label="产出任务" name="producers">
          <div class="empty">产出此表的任务视图留待后续接入（离线开发 / 工作流血缘本期未解析）。</div>
        </el-tab-pane>

        <!-- 版本（仅库表） -->
        <el-tab-pane v-if="assetType === 'table'" label="版本" name="version">
          <div style="margin-bottom:6px"><el-button size="small" type="warning" @click="forceVer">强制更新到最新版本</el-button></div>
          <el-table :data="versions" size="small" border>
            <el-table-column prop="version_n" label="版本" width="70" />
            <el-table-column prop="change_type" label="类型" width="90" />
            <el-table-column prop="change_detail" label="变化" show-overflow-tooltip />
            <el-table-column prop="created_time" label="时间" width="160" />
            <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link size="small" type="primary" @click="applyVer(row.version_n)">应用</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { VChart } from '@/echarts'
import { api, errMsg } from '@/api'

const theme = 'tech-dark'
const assetType = ref<'table' | 'api' | 'file'>('table')
const kw = ref('')
const loading = ref(false)
const rows = ref<any[]>([])
const catalog = ref<any[]>([])
const page = ref(1)
const pageSize = ref(20)

// 详情
const detailDlg = ref(false)
const detailTab = ref('overview')
const detail = ref<any>({})
const svc = ref<any>({})
const cols = ref<any[]>([])
const fileCols = ref<any[]>([])
const biz = ref<any>({})
const saving = ref(false)
const versions = ref<any[]>([])
// 血缘
const lineageMode = ref<'lineage' | 'impact'>('lineage')
const graphData = ref<any>(null)
// 检索
const showResult = ref(false)
const searchResult = ref<any>(null)
// 下拉
const standards = ref<any[]>([])
const subjects = ref<any[]>([])
const subjectOpts = computed(() => { const out: any[] = []; for (const n of subjects.value) { out.push(n); if (n.children) for (const c of n.children) out.push(c) } return out })

const pagedRows = computed(() => {
  const s = (page.value - 1) * pageSize.value
  return rows.value.slice(s, s + pageSize.value)
})

const detailTitle = computed(() => {
  if (assetType.value === 'table') return `表 · ${detail.value.table_name || ''}`
  if (assetType.value === 'api') return `接口 · ${svc.value.name || ''}`
  return `文件 · ${detail.value.cn_name || detail.value.path || ''}`
})

const CAT_COLOR: Record<string, string> = { ds: '#2f6bff', table: '#00e0ff', external: '#ffb020', field: '#7f93bf' }
const EDGE_COLOR: Record<string, string> = { OFFLINE: '#2ee6a6', STREAM: '#7c5cff', EXPORT: '#ffb020', CONTAIN: '#3a4a7a', FIELD: '#7f93bf' }
const graphOption = computed(() => {
  const data = (graphData.value?.nodes || []).map((n: any) => ({ id: n.id, name: n.label, symbolSize: n.category === 'ds' ? 50 : n.category === 'field' ? 16 : 36, itemStyle: { color: CAT_COLOR[n.category] || '#888', borderColor: n.pending ? '#ff4d6d' : 'rgba(255,255,255,0.2)', borderWidth: n.pending ? 3 : 1 } }))
  const links = (graphData.value?.links || []).map((l: any) => ({ source: l.source, target: l.target, lineStyle: { color: EDGE_COLOR[l.jobType] || '#888', curveness: 0.2 }, value: l.jobName }))
  return { tooltip: { formatter: (p: any) => (p.dataType === 'edge' ? (p.data.value || '') : p.data.name) }, series: [{ type: 'graph', layout: 'force', roam: true, draggable: true, force: { repulsion: 150, edgeLength: 90, gravity: 0.08 }, label: { show: true, position: 'right', color: '#aebfe2', fontSize: 11 }, edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 8, emphasis: { focus: 'adjacency' }, data, links }] }
})

async function loadList() {
  loading.value = true
  page.value = 1
  try {
    if (assetType.value === 'table') rows.value = await api.govMetaList({})
    else if (assetType.value === 'api') rows.value = await api.govMetaApiList()
    else rows.value = await api.govMetaFileList({})
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function onType() { graphData.value = null; loadList() }

async function openDetail(row: any) {
  detailTab.value = 'overview'
  graphData.value = null
  try {
    if (assetType.value === 'table') {
      const d: any = await api.govMetaDetail(row.id)
      detail.value = d; cols.value = d.columns_json ? JSON.parse(d.columns_json) : []; biz.value = { ...d }
      versions.value = await api.govMetaVersionList(row.id)
    } else if (assetType.value === 'api') {
      const d: any = await api.govMetaApiDetail(row.service_id)
      svc.value = d; biz.value = { service_id: row.service_id, ...(d.meta || {}) }
    } else {
      const d: any = await api.govMetaFileDetail(row.id)
      detail.value = d; fileCols.value = d.columns_json ? JSON.parse(d.columns_json) : []; biz.value = { ...d }
    }
    detailDlg.value = true
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function saveBiz() {
  saving.value = true
  try {
    if (assetType.value === 'table') await api.govMetaSave(biz.value)
    else if (assetType.value === 'api') await api.govMetaApiSave(biz.value)
    else { biz.value.columns_json = JSON.stringify(fileCols.value); await api.govMetaFileSave(biz.value) }
    ElMessage.success('已保存')
    if (assetType.value === 'table') { const d: any = await api.govMetaDetail(biz.value.id); detail.value = d; biz.value = { ...d } }
    await loadList()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
function addCol() { fileCols.value.push({ name: '', type: '' }) }
async function applyVer(v: number) {
  try { await api.govMetaVersionApply(detail.value.id, v); ElMessage.success('已应用 v' + v); const d: any = await api.govMetaDetail(detail.value.id); detail.value = d; cols.value = d.columns_json ? JSON.parse(d.columns_json) : [] }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function forceVer() {
  try { await api.govMetaVersionForce(detail.value.id); ElMessage.success('已强制更新'); versions.value = await api.govMetaVersionList(detail.value.id); const d: any = await api.govMetaDetail(detail.value.id); detail.value = d; cols.value = d.columns_json ? JSON.parse(d.columns_json) : [] }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function drawLineage() {
  if (assetType.value !== 'table') return
  try {
    graphData.value = lineageMode.value === 'lineage'
      ? await api.govMetaLineage(detail.value.ds_id, detail.value.schema_name, detail.value.table_name)
      : await api.govMetaImpact(detail.value.ds_id, detail.value.schema_name, detail.value.table_name)
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function doSearch() {
  if (!kw.value) return
  try { searchResult.value = await api.govMetaSearch(kw.value); showResult.value = true } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function goto(type: string, id: number) {
  showResult.value = false
  assetType.value = type as any
  await loadList()
  const row = rows.value.find((r: any) => r.id === id || r.service_id === id)
  if (row) openDetail(row)
}

watch(detailTab, (t) => { if (t === 'lineage' && assetType.value === 'table' && !graphData.value) drawLineage() })
onMounted(async () => { try { catalog.value = await api.assetCatalogTree(); subjects.value = await api.govSubjects(); standards.value = await api.secStandards() } catch { /* */ } await loadList() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { color: var(--tech-text-muted); font-size: 13px; }
.search-bar { margin-bottom: 4px; }
.sec { font-weight: 600; margin: 12px 0 6px; color: var(--tech-text-muted); font-size: 13px; }
.empty { color: var(--tech-text-muted); font-size: 13px; padding: 30px 0; text-align: center; }
.chart { width: 100%; height: 520px; }
</style>
