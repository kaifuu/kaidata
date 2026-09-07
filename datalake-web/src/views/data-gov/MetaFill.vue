<template>
  <div class="dl-card">
    <div class="page-head">
      <div>
        <h2><el-icon><EditPen /></el-icon> 元数据补录</h2>
        <p>补录工作台：完整度看板 + 待补录清单；支持新增登记（采集覆盖不到的表）、结构手工维护（记 MANUAL 版本立即生效）、Excel 批量导入</p>
      </div>
      <div class="head-stats" v-if="stats">
        <span>整体完整度<b>{{ stats.overall }}%</b></span>
        <span>待补录<b>{{ stats.table.filling + stats.api.filling + stats.file.filling }}</b></span>
      </div>
    </div>

    <div class="kpi-grid" v-if="stats">
      <div class="kpi-card" v-for="k in kpis" :key="k.lab" :style="{ '--chip': k.color }">
        <div class="kpi-icon"><el-icon :size="20"><component :is="k.icon" /></el-icon></div>
        <div class="kpi-body">
          <div class="kpi-top"><span class="kpi-lab">{{ k.lab }}</span><span class="kpi-val">{{ k.pct }}%</span></div>
          <el-progress :percentage="k.pct" :stroke-width="6" :show-text="false" :color="k.color" />
          <div class="kpi-sub">已完成 {{ k.filled }} / {{ k.total }}</div>
        </div>
      </div>
    </div>

    <div class="panel" style="margin-top:16px">
      <div class="ct"><el-icon><Document /></el-icon> 待补录清单<span class="ct-sub">{{ type === 'api' ? 'API' : type === 'file' ? 'FILE' : 'TABLE' }}</span></div>
      <div class="filter-bar">
        <el-radio-group v-model="type" size="small" @change="onType">
          <el-radio-button label="table">库表</el-radio-button>
          <el-radio-button label="api">接口</el-radio-button>
          <el-radio-button label="file">文件</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="status" size="small" @change="onPage1">
          <el-radio-button label="filling">待补录</el-radio-button>
          <el-radio-button label="filled">已完成</el-radio-button>
        </el-radio-group>
        <el-select v-if="type === 'table'" v-model="dsId" placeholder="数据源" size="small" clearable filterable style="width:170px" @change="onPage1">
          <el-option v-for="d in dsList" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-input v-model="kw" placeholder="搜索表名/中文名" size="small" style="width:190px" clearable @keyup.enter="onPage1" />
        <el-button size="small" @click="onPage1">查询</el-button>
        <div class="toolbar-actions">
          <template v-if="type === 'table'">
            <el-button size="small" type="primary" @click="openReg"><el-icon><Plus /></el-icon> 新增登记</el-button>
            <el-button size="small" @click="downloadTemplate"><el-icon><Download /></el-icon> 模板</el-button>
            <el-button size="small" :loading="importing" @click="importClick"><el-icon><Upload /></el-icon> 导入 Excel</el-button>
            <input ref="importInput" type="file" accept=".xlsx,.xls" style="display:none" @change="doImport" />
          </template>
        </div>
      </div>

      <!-- 库表：富化列表（数据源/层级/主题域/待生效版本） -->
      <el-table v-if="type === 'table'" :data="rows" size="small" stripe border v-loading="loading">
        <el-table-column label="名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="tbl-name">{{ row.schema_name ? row.schema_name + '.' : '' }}{{ row.table_name }}</code>
            <div class="muted" v-if="row.cn_name">{{ row.cn_name }}</div>
          </template>
        </el-table-column>
        <el-table-column label="数据源" width="120" show-overflow-tooltip>
          <template #default="{ row }"><span v-if="row.ds_name">{{ row.ds_name }}</span><span v-else class="muted">#{{ row.ds_id }}</span></template>
        </el-table-column>
        <el-table-column label="层级" width="70">
          <template #default="{ row }"><el-tag v-if="row.layer_code" size="small" type="warning" effect="plain">{{ row.layer_code }}</el-tag><span v-else class="muted">—</span></template>
        </el-table-column>
        <el-table-column label="主题域" width="100" show-overflow-tooltip>
          <template #default="{ row }"><span v-if="row.subject_name">{{ row.subject_name }}</span><span v-else class="muted">—</span></template>
        </el-table-column>
        <el-table-column label="填充度" width="130">
          <template #default="{ row }"><el-progress :percentage="row.fill_percent || 0" :stroke-width="8" :status="(row.fill_percent || 0) >= 100 ? 'success' : ''" /></template>
        </el-table-column>
        <el-table-column label="待生效" width="110">
          <template #default="{ row }">
            <template v-if="row.pending_versions > 0">
              <el-tag size="small" type="warning">v+{{ row.pending_versions }}</el-tag>
              <el-button link size="small" type="primary" @click="applyVersion(row)">应用</el-button>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最近采集" width="140">
          <template #default="{ row }">{{ fmtTime(row.synced_time) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="openFill(row)">补录</el-button>
            <el-button link size="small" type="primary" @click="openCols(row)">结构</el-button>
            <el-button link size="small" type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- 接口/文件：基础列表 -->
      <el-table v-else :data="rows" size="small" stripe border v-loading="loading">
        <el-table-column prop="name" label="名称" min-width="220" show-overflow-tooltip />
        <el-table-column label="填充度" width="160">
          <template #default="{ row }"><el-progress :percentage="row.fill_percent || 0" :stroke-width="8" :status="(row.fill_percent || 0) >= 100 ? 'success' : ''" /></template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }"><el-button link size="small" type="primary" @click="openFill(row)">补录</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top:10px;justify-content:flex-end" :current-page="page" :page-size="size" :total="total" size="small" background layout="total, prev, pager, next" @current-change="v => { page = v; load() }" />
    </div>

    <!-- 补录右抽屉（全业务字段；合并保存，不抹未填列） -->
    <el-drawer v-model="fillDlg" :title="'补录 · ' + fillTitle" size="680px">
      <el-form :model="biz" label-width="92px" size="small">
        <el-divider content-position="left">基础信息</el-divider>
        <el-form-item label="中文名"><el-input v-model="biz.cn_name" placeholder="业务中文名（留空不计填充度）" /></el-form-item>
        <el-form-item label="所属部门"><el-input v-model="biz.dept" /></el-form-item>
        <el-form-item label="应用系统"><el-input v-model="biz.app_system" /></el-form-item>
        <el-form-item label="数据分类"><el-input v-model="biz.data_category" placeholder="如 业务数据 / 参考数据" /></el-form-item>
        <template v-if="curType === 'table'">
          <el-divider content-position="left">数仓归属</el-divider>
          <el-form-item label="层级">
            <el-select v-model="biz.layer_code" clearable style="width:100%">
              <el-option v-for="l in layers" :key="l.code" :label="l.code + ' · ' + l.name" :value="l.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="资源属性">
            <el-select v-model="biz.resource_attr" clearable filterable allow-create style="width:100%">
              <el-option v-for="v in ['原始', '加工', '衍生']" :key="v" :label="v" :value="v" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item label="主题域">
          <el-select v-model="biz.subject_id" clearable filterable style="width:100%">
            <el-option v-for="s in subjectOpts" :key="s.id" :label="s.label" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="共享类型">
          <el-select v-model="biz.share_type" clearable filterable allow-create style="width:100%">
            <el-option v-for="v in ['公开', '内部', '受限']" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">管理与安全</el-divider>
        <el-form-item label="安全级别">
          <el-select v-model="biz.security_level" clearable style="width:100%">
            <el-option v-for="st in standards" :key="st.code" :label="st.name" :value="st.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源管理员"><el-input v-model="biz.admin_owner" /></el-form-item>
        <el-form-item label="联系方式"><el-input v-model="biz.admin_contact" /></el-form-item>
        <el-form-item label="业务描述"><el-input v-model="biz.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <div class="muted" style="margin:0 0 8px">保存为合并更新：未填写的项保留原值，不会清空其它业务属性。</div>
      <template #footer>
        <el-button size="small" @click="fillDlg = false">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-drawer>

    <!-- 新增登记（采集覆盖不到的表） -->
    <el-dialog v-model="regDlg" title="新增登记" width="480px">
      <el-form :model="regForm" label-width="80px" size="small">
        <el-form-item label="数据源">
          <el-select v-model="regForm.ds_id" filterable style="width:100%" placeholder="选择表所在数据源">
            <el-option v-for="d in dsList" :key="d.id" :label="d.name + '（' + d.type + '）'" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="库名"><el-input v-model="regForm.schema_name" placeholder="如 ods（可留空）" /></el-form-item>
        <el-form-item label="表名"><el-input v-model="regForm.table_name" placeholder="如 dem_user" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="regForm.comment" /></el-form-item>
      </el-form>
      <div class="muted">登记后可补录业务信息、手工维护结构（记 MANUAL 版本）；后续探查/采集发现结构变化只记待生效版本，不会静默覆盖手工内容。</div>
      <template #footer>
        <el-button size="small" @click="regDlg = false">取消</el-button>
        <el-button type="primary" size="small" :loading="creating" @click="createGo">登记并补录</el-button>
      </template>
    </el-dialog>

    <!-- 结构编辑（手工修正，MANUAL 版本立即生效） -->
    <el-drawer v-model="colDlg" :title="'结构编辑 · ' + colTitle" size="760px">
      <el-form label-width="80px" size="small" style="margin-bottom:8px">
        <el-form-item label="表注释"><el-input v-model="colForm.comment" /></el-form-item>
      </el-form>
      <el-table :data="colForm.cols" size="small" border v-loading="colLoading" max-height="460">
        <el-table-column type="index" label="#" width="42" />
        <el-table-column label="字段名" min-width="170">
          <template #default="{ row }"><el-input v-model="row.name" size="small" placeholder="name" /></template>
        </el-table-column>
        <el-table-column label="类型" width="180">
          <template #default="{ row }"><el-input v-model="row.type" size="small" placeholder="VARCHAR(64)" /></template>
        </el-table-column>
        <el-table-column label="注释" min-width="170">
          <template #default="{ row }"><el-input v-model="row.comment" size="small" /></template>
        </el-table-column>
        <el-table-column label="" width="50">
          <template #default="{ $index }"><el-button link size="small" type="danger" @click="colForm.cols.splice($index, 1)">删</el-button></template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin-top:8px" @click="colForm.cols.push({ name: '', type: '', comment: '' })"><el-icon><Plus /></el-icon> 添加字段</el-button>
      <div class="muted" style="margin-top:8px">保存时与现行结构比对：有差异则登记 source=MANUAL 的版本并立即生效（现行结构永不被采集静默覆盖）。</div>
      <template #footer>
        <el-button size="small" @click="colDlg = false">取消</el-button>
        <el-button type="primary" size="small" :loading="colSaving" @click="saveCols">保存结构</el-button>
      </template>
    </el-drawer>

    <!-- Excel 导入结果 -->
    <el-dialog v-model="importDlg" title="批量补录结果" width="680px">
      <div style="margin-bottom:8px" v-if="importRes">
        <el-tag type="success" style="margin-right:8px">成功 {{ importRes.ok }}</el-tag>
        <el-tag type="danger" style="margin-right:8px">失败 {{ importRes.fail }}</el-tag>
        <span class="muted">共 {{ importRes.total }} 行（按 库名+表名 匹配已登记表，合并保存不抹未填列）</span>
      </div>
      <el-table :data="importRes?.results || []" size="small" border max-height="380">
        <el-table-column prop="schema" label="库" width="110" />
        <el-table-column prop="table" label="表" width="150" />
        <el-table-column label="结果" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ok' ? 'success' : row.status === 'skip' ? 'info' : 'danger'">{{ row.status === 'ok' ? '成功' : row.status === 'skip' ? '跳过' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="msg" label="说明" min-width="240" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Document, Coin, Connection, Files, Plus, Download, Upload } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const stats = ref<any>(null)
const type = ref<'table' | 'api' | 'file'>('table')
const status = ref<'filling' | 'filled'>('filling')
const kw = ref('')
const dsId = ref<number | undefined>(undefined)
const rows = ref<any[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const standards = ref<any[]>([])
const layers = ref<any[]>([])
const subjects = ref<any[]>([])
const dsList = ref<any[]>([])

const kpis = computed(() => {
  if (!stats.value) return []
  const t = stats.value
  return [
    { icon: Coin, color: '#1557ef', lab: '库表', pct: t.table.avg || 0, filled: t.table.filled, total: t.table.total },
    { icon: Connection, color: '#7c5cff', lab: '接口', pct: t.api.avg || 0, filled: t.api.filled, total: t.api.total },
    { icon: Files, color: '#16b364', lab: '文件', pct: t.file.avg || 0, filled: t.file.filled, total: t.file.total },
  ]
})

// 主题域拍平（缩进标层级），供下拉
const subjectOpts = computed(() => {
  const out: any[] = []
  const walk = (nodes: any[], depth: number) => nodes.forEach((n: any) => {
    out.push({ id: n.id, label: ' '.repeat(depth * 2) + n.code + ' / ' + n.name })
    walk(n.children || [], depth + 1)
  })
  walk(subjects.value, 0)
  return out
})

function fmtTime(s?: string) { return s ? String(s).replace('T', ' ').slice(0, 19) : '—' }

async function loadStats() { try { stats.value = await api.govMetaFillStats() } catch { /* */ } }
async function load() {
  loading.value = true
  try {
    const r: any = await api.govMetaFillList({
      type: type.value, status: status.value, kw: kw.value || undefined,
      dsId: type.value === 'table' ? dsId.value : undefined, page: page.value, size: size.value,
    })
    rows.value = r.records || []
    total.value = r.total || 0
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function onPage1() { page.value = 1; load() }
function onType() { page.value = 1; dsId.value = undefined; load() }

// ===== 补录（右抽屉，全字段合并保存） =====
const fillDlg = ref(false)
const biz = ref<any>({})
const saving = ref(false)
const curType = ref<'table' | 'api' | 'file'>('table')   // 打开抽屉时的类型（避免列表切换串数据）
const fillTitle = computed(() => {
  const b = biz.value
  return b.schema_name ? b.schema_name + '.' + (b.table_name || '') : (b.table_name || b.name || b.cn_name || '')
})
async function openFill(row: any) {
  curType.value = type.value
  // 库表行已富化全业务列；接口/文件行仅 3 列，先拉详情防部分 payload 抹列
  try {
    if (type.value === 'table') biz.value = { ...row }
    else if (type.value === 'api') biz.value = { ...(await api.govMetaApiDetail(row.id)) }
    else biz.value = { ...(await api.govMetaFileDetail(row.id)) }
  } catch (e: any) { biz.value = { ...row } }
  fillDlg.value = true
}
async function save() {
  saving.value = true
  try {
    // 显式全字段 payload：清空下拉（undefined）也如实落库，不再依赖 {...row}
    const p: any = { id: biz.value.id }
    for (const k of ['cn_name', 'dept', 'app_system', 'admin_owner', 'admin_contact', 'data_category', 'security_level', 'description', 'share_type'])
      p[k] = biz.value[k] == null ? '' : biz.value[k]
    p.subject_id = biz.value.subject_id || 0
    if (curType.value === 'table') { p.layer_code = biz.value.layer_code || ''; p.resource_attr = biz.value.resource_attr || '' }
    if (curType.value === 'api') { p.service_id = biz.value.service_id || biz.value.id }
    if (curType.value === 'table') await api.govMetaSave(p)
    else if (curType.value === 'api') await api.govMetaApiSave(p)
    else await api.govMetaFileSave(p)
    ElMessage.success('已保存')
    fillDlg.value = false
    await Promise.all([loadStats(), load()])
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

// ===== 删除（被资产挂载时后端阻断） =====
async function del(row: any) {
  const name = row.schema_name ? row.schema_name + '.' + row.table_name : (row.table_name || row.name)
  try { await ElMessageBox.confirm(`删除元数据「${name}」？结构版本一并删除，不可恢复`, '删除', { type: 'warning', confirmButtonText: '删除' }) } catch { return }
  try { await api.govMetaDelete(row.id); ElMessage.success('已删除'); await Promise.all([loadStats(), load()]) }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== 应用待生效版本（采集/探查发现的结构变化） =====
async function applyVersion(row: any) {
  const target = (row.current_version || 0) + (row.pending_versions || 0)
  try { await ElMessageBox.confirm(`应用待生效结构 v${target}？将替换现行结构（v${row.current_version || 0}）`, '应用版本', { type: 'warning' }) } catch { return }
  try { await api.govMetaVersionApply(row.id, target); ElMessage.success(`已应用 v${target}`); await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== 新增登记 =====
const regDlg = ref(false)
const creating = ref(false)
const regForm = reactive<any>({ ds_id: undefined, schema_name: '', table_name: '', comment: '' })
function openReg() { Object.assign(regForm, { ds_id: undefined, schema_name: '', table_name: '', comment: '' }); regDlg.value = true }
async function createGo() {
  if (!regForm.ds_id) return ElMessage.warning('请选择数据源')
  if (!regForm.table_name.trim()) return ElMessage.warning('表名必填')
  creating.value = true
  try {
    const r: any = await api.govMetaSave({ id: 0, ...regForm })
    ElMessage.success('登记成功，请继续补录业务信息')
    regDlg.value = false
    await Promise.all([loadStats(), load()])
    fillDlg.value = true
    curType.value = 'table'
    biz.value = { id: r.id, ds_id: regForm.ds_id, schema_name: regForm.schema_name, table_name: regForm.table_name, cn_name: '' }
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { creating.value = false }
}

// ===== 结构编辑（MANUAL 版本立即生效） =====
const colDlg = ref(false)
const colLoading = ref(false)
const colSaving = ref(false)
const colTitle = ref('')
const colForm = reactive<any>({ id: 0, comment: '', cols: [] as any[] })
async function openCols(row: any) {
  colTitle.value = (row.schema_name ? row.schema_name + '.' : '') + row.table_name
  colDlg.value = true; colLoading.value = true
  try {
    const d: any = await api.govMetaDetail(row.id)
    colForm.id = row.id
    colForm.comment = d.comment || ''
    let cols: any[] = []
    try { cols = JSON.parse(d.columns_json || '[]') } catch { cols = [] }
    colForm.cols = cols.map((c: any) => ({ name: c.name || '', type: c.type || '', comment: c.comment || '' }))
    if (!colForm.cols.length) colForm.cols.push({ name: '', type: '', comment: '' })
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { colLoading.value = false }
}
async function saveCols() {
  colSaving.value = true
  try {
    const r: any = await api.govMetaColumns({ id: colForm.id, comment: colForm.comment, columns: colForm.cols })
    ElMessage.success(`结构已保存（现行 v${r.version}，共 ${r.columns} 字段）`)
    colDlg.value = false
    await load()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { colSaving.value = false }
}

// ===== Excel 批量导入 =====
const importInput = ref<any>(null)
const importing = ref(false)
const importDlg = ref(false)
const importRes = ref<any>(null)
async function downloadTemplate() {
  try {
    const blob: any = await api.govMetaFillTemplate()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = '元数据补录导入模板.xlsx'; a.click()
    URL.revokeObjectURL(url)
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
function importClick() { if (importInput.value) { importInput.value.value = ''; importInput.value.click() } }
async function doImport(ev: Event) {
  const f = (ev.target as HTMLInputElement).files?.[0]
  if (!f) return
  importing.value = true
  try {
    const r: any = await api.govMetaFillImport(f)
    importRes.value = r
    importDlg.value = true
    await Promise.all([loadStats(), load()])
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { importing.value = false }
}

onMounted(async () => {
  await Promise.all([
    api.secStandards().then((r: any) => { standards.value = r }).catch(() => { }),
    api.govLayers().then((r: any) => { layers.value = r }).catch(() => { }),
    api.govSubjects().then((r: any) => { subjects.value = r }).catch(() => { }),
    api.daSources().then((r: any) => { dsList.value = r }).catch(() => { }),
  ])
  await Promise.all([loadStats(), load()])
})
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 18px; }
.page-head h2 { margin: 0; font-size: 18px; font-weight: 600; color: var(--tech-text); display: flex; align-items: center; gap: 8px; }
.page-head h2 .el-icon { color: var(--tech-primary); }
.page-head p { margin: 6px 0 0; color: var(--tech-text-muted); font-size: 13px; }
.head-stats { display: flex; gap: 22px; }
.head-stats span { font-size: 13px; color: var(--tech-text-muted); }
.head-stats b { color: var(--tech-text); font-size: 15px; font-weight: 600; margin-left: 4px; }
.kpi-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.kpi-card { display: flex; align-items: center; gap: 14px; padding: 18px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); transition: border-color .15s; }
.kpi-card:hover { border-color: var(--tech-primary); }
.kpi-icon { width: 42px; height: 42px; border-radius: 10px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: color-mix(in srgb, var(--chip) 13%, var(--tech-panel)); color: var(--chip); }
.kpi-body { flex: 1; min-width: 0; }
.kpi-top { display: flex; justify-content: space-between; align-items: baseline; }
.kpi-lab { font-size: 12px; color: var(--tech-text-muted); }
.kpi-val { font-size: 22px; font-weight: 700; color: var(--tech-text); font-variant-numeric: tabular-nums; }
.kpi-sub { font-size: 11px; color: var(--tech-text-muted); opacity: .75; margin-top: 6px; }
.panel { padding: 16px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); }
.ct { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 14px; color: var(--tech-text); margin-bottom: 10px; }
.ct .el-icon { color: var(--tech-primary); }
.ct-sub { margin-left: auto; font-size: 11px; letter-spacing: 2px; color: var(--tech-text-muted); opacity: .55; font-weight: 400; }
.filter-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.toolbar-actions { margin-left: auto; display: inline-flex; gap: 8px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.tbl-name { font-size: 12.5px; }
</style>
