<template>
  <div class="dl-card">
    <div class="card-title"><span>数据仓库 · 分层管理</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="分层管理" name="layer">
        <div class="layer-layout">
          <!-- 左：分层树 -->
          <div class="layer-tree-pane">
            <div class="tree-head">
              <span class="th-t">数据分层</span>
              <el-button link size="small" type="primary" @click="openLayer()"><el-icon><Plus /></el-icon> 新增层级</el-button>
            </div>
            <el-tree ref="treeRef" :data="layerTree" node-key="code" highlight-current default-expand-all
              :expand-on-click-node="false" @node-click="onPickLayer">
              <template #default="{ data }">
                <div class="tree-node">
                  <span class="tn-label">
                    <el-icon class="tn-ic"><Folder /></el-icon>
                    <b v-if="data.code">{{ data.code }}</b>
                    <span class="tn-name">{{ data.name }}</span>
                  </span>
                  <span class="tn-right" @click.stop>
                    <span v-if="data.code" class="tn-badge" :class="{ zero: !bindCount(data.code) }" title="已绑定数据源数">{{ bindCount(data.code) }}</span>
                    <span v-if="data.code" class="tn-ops">
                      <el-icon title="编辑" @click.stop="openLayer(data)"><Edit /></el-icon>
                      <el-icon title="删除" class="tn-danger" @click.stop="delLayer(data)"><Delete /></el-icon>
                    </span>
                  </span>
                </div>
              </template>
            </el-tree>
            <div class="tree-hint muted">分层（ODS/DWD/DWS/ADS/DIM）绑定数据源后，数据探查/接入的「所属层级」即从此选取目标数据源。</div>
          </div>
          <!-- 右：绑定数据源列表 -->
          <div class="layer-main-pane">
            <div class="dl-toolbar">
              <el-input v-model="bindKw" placeholder="数据源名称" size="small" clearable style="width:160px" />
              <el-select v-model="bindType" placeholder="类型" size="small" clearable filterable style="width:120px">
                <el-option v-for="t in bindTypeOpts" :key="t" :label="t" :value="t" />
              </el-select>
              <el-select v-model="bindStatus" placeholder="状态" size="small" clearable style="width:110px">
                <el-option label="正常(NORMAL)" value="NORMAL" />
                <el-option label="停用(DISABLED)" value="DISABLED" />
              </el-select>
              <div class="toolbar-actions">
                <el-button size="small" @click="resetBindQuery">重置</el-button>
                <el-button size="small" type="primary" :disabled="!curLayer" @click="openBind">
                  <el-icon><Plus /></el-icon> 绑定数据源<span v-if="curLayer" class="bind-to">→ {{ curLayer }}</span>
                </el-button>
              </div>
            </div>
            <el-table :data="bindPaged" size="small" stripe border v-loading="loading">
              <el-table-column label="数据源" min-width="150">
                <template #default="{ row }">
                  <b>{{ row.ds_name || ('ds#' + row.datasource_id) }}</b>
                  <span class="muted ds-id">#{{ row.datasource_id }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="100">
                <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.ds_type || '—' }}</el-tag></template>
              </el-table-column>
              <el-table-column label="内部存储" width="80" align="center">
                <template #default="{ row }">
                  <el-tag v-if="isInternal(row.ds_type)" size="small" type="success">内部</el-tag>
                  <span v-else class="muted">外部</span>
                </template>
              </el-table-column>
              <el-table-column label="地址" min-width="160">
                <template #default="{ row }"><code class="addr">{{ row.host ? row.host + ':' + row.port : '—' }}</code>{{ row.db_name ? ' / ' + row.db_name : '' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="84">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.ds_status === 'NORMAL' ? 'success' : 'info'">{{ row.ds_status || '—' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="!curLayer" label="所属层" width="80">
                <template #default="{ row }"><el-tag size="small" type="warning" effect="plain">{{ row.layer_code }}</el-tag></template>
              </el-table-column>
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ row }">
                  <el-button link size="small" type="danger" @click="unbind(row)">解绑</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="empty-tip muted" v-if="!bindFiltered.length && !loading">{{ curLayer ? `层级 ${curLayer} 暂未绑定数据源，点右上「绑定数据源」添加` : '暂无绑定关系，左侧选择层级后绑定' }}</div>
            <div class="dl-pagination">
              <el-pagination :current-page="bindPage.page" :page-size="bindPage.size" :total="bindFiltered.length"
                :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
                @size-change="onBindSizeChange" @current-change="onBindPageChange" />
            </div>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="分层画像" name="stats">
        <!-- 顶部：占比图 + 行数分布 -->
        <div class="stats-charts" v-loading="loadingStats">
          <div class="chart-panel">
            <div class="cp-t"><el-icon><PieChart /></el-icon> 各层表数占比<span class="muted">共 {{ statTotalTables }} 张</span></div>
            <v-chart :option="pieOption" :theme="theme.chartTheme" autoresize class="ch" />
          </div>
          <div class="chart-panel">
            <div class="cp-t"><el-icon><Histogram /></el-icon> 各层行数分布<span class="muted">共 {{ fmtNum(statTotalRows) }} 行</span></div>
            <v-chart :option="barOption" :theme="theme.chartTheme" autoresize class="ch" />
          </div>
        </div>
        <!-- 画像卡片（点击钻取表清单） -->
        <el-row :gutter="10">
          <el-col v-for="s in stats" :key="s.code" :span="6" style="margin-bottom:10px">
            <div class="stat-card stat-click" @click="openLayerTables(s)">
              <div class="stat-head">
                <b>{{ s.code }}</b><span class="muted">{{ s.name }}</span>
                <el-tag size="small" :type="s.source === 'physical' ? 'success' : 'info'" effect="plain">{{ s.source === 'physical' ? '实测' : '登记' }}</el-tag>
              </div>
              <div class="stat-row"><span>物理表</span><b>{{ s.tables }}<span class="unit">张</span></b></div>
              <div class="stat-row"><span>行数合计</span><b>{{ fmtNum(s.rows) }}</b></div>
              <div class="stat-row"><span>存储占用</span><b>{{ fmtSize(s.size_bytes) }}</b></div>
              <div class="stat-row"><span>绑定数据源</span><b>{{ s.ds_count ?? 0 }}<span class="unit">个</span></b></div>
              <div class="stat-row">
                <span>命名合规</span>
                <b>
                  <el-tag v-if="!s.naming_checked" size="small" type="info" effect="plain">未配置</el-tag>
                  <el-tag v-else-if="s.naming_violate === 0" size="small" type="success">{{ s.naming_checked }}/{{ s.naming_checked }}</el-tag>
                  <el-tag v-else size="small" type="warning">{{ s.naming_checked - s.naming_violate }}/{{ s.naming_checked }}</el-tag>
                </b>
              </div>
              <div class="stat-foot">
                <span class="muted">最近更新 {{ fmtShort(s.last_update) }}</span>
                <span class="link">表清单 →</span>
              </div>
            </div>
          </el-col>
        </el-row>
        <div class="hint"><el-icon><InfoFilled /></el-icon> 点击卡片查看层内表清单；存储/行数为 StarRocks information_schema 实测，命名合规来自命名巡检规则，绑定数与分层管理共享。</div>
      </el-tab-pane>
      <el-tab-pane label="命名巡检" name="naming">
        <div style="margin-bottom:10px"><el-button size="small" type="primary" :loading="loadingNaming" @click="runNamingCheck">立即巡检</el-button></div>
        <template v-if="naming">
          <el-alert v-if="naming.checked === 0" title="没有可巡检的表（需先在元数据采集登记表且分层配置命名规范）" type="info" :closable="false" />
          <el-alert v-else-if="naming.violate === 0" :title="`巡检通过：${naming.checked} 张表全部符合分层命名规范`" type="success" :closable="false" style="margin-bottom:10px" />
          <el-alert v-else :title="`发现 ${naming.violate}/${naming.checked} 张表命名不规范`" type="warning" :closable="false" style="margin-bottom:10px" />
          <el-table v-if="naming.violations?.length" :data="naming.violations" size="small" border max-height="420">
            <el-table-column prop="layer" label="层" width="90" />
            <el-table-column prop="table" label="表名" min-width="200"><template #default="{ row }"><code>{{ row.table }}</code></template></el-table-column>
            <el-table-column prop="pattern" label="命名规范" width="140" />
            <el-table-column prop="suggest" label="建议表名" min-width="200"><template #default="{ row }"><span class="suggest">{{ row.suggest }}</span></template></el-table-column>
          </el-table>
        </template>
      </el-tab-pane>
      <el-tab-pane label="主题域" name="subject">
        <div style="margin-bottom:10px"><el-button type="primary" size="small" @click="openSubject()"><el-icon><Plus /></el-icon> 新增主题域</el-button></div>
        <el-table :data="subjectPaged" row-key="id" size="small" border default-expand-all>
          <el-table-column prop="code" label="编码" width="140" />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="openSubject(row, null)">编辑</el-button>
              <el-button link size="small" type="primary" @click="openSubject(null, row)">加子域</el-button>
              <el-button link size="small" type="danger" @click="delSubject(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="dl-pagination">
          <el-pagination :current-page="subjectPage.page" :page-size="subjectPage.size" :total="subjects.length"
            :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
            @size-change="onSubjectSizeChange" @current-change="onSubjectPageChange" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 层级编辑 -->
    <el-drawer v-model="layerDlg" :title="layerForm.code ? '编辑层级' : '新增层级'" size="560px">
      <el-form :model="layerForm" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="layerForm.code" :disabled="!!layerForm.code" placeholder="如 dwd" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="layerForm.name" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="layerForm.sort" :min="0" /></el-form-item>
        <el-form-item label="命名规范"><el-input v-model="layerForm.naming_pattern" placeholder="^dwd_" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="layerDlg = false">取消</el-button><el-button type="primary" @click="saveLayer">保存</el-button></template>
    </el-drawer>

    <!-- 绑定数据源（多选抽屉） -->
    <el-drawer v-model="bindDlg" :title="`绑定数据源 → 层级 ${curLayer}`" size="760px" destroy-on-close>
      <div class="dl-toolbar" style="padding:0;margin-bottom:10px">
        <el-input v-model="bindPickKw" placeholder="名称/类型检索" size="small" clearable style="width:200px" />
        <span class="muted">已绑定 {{ boundIds.length }} 个，可选 {{ bindCandidates.length }} 个</span>
      </div>
      <el-table ref="bindPickRef" :data="bindCandidates" row-key="id" size="small" border max-height="460"
        @selection-change="onBindSelChange">
        <el-table-column type="selection" width="42" :selectable="canPickDs" />
        <el-table-column label="数据源" min-width="150">
          <template #default="{ row }"><b>{{ row.name }}</b><span class="muted ds-id">#{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column label="地址" min-width="150">
          <template #default="{ row }"><code class="addr">{{ row.host }}:{{ row.port }}</code> / {{ row.db_name }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag v-if="boundIds.includes(row.id)" size="small" type="info">已绑定</el-tag>
            <el-tag v-else size="small" :type="row.status === 'NORMAL' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="muted" style="margin-right:12px">已勾选 {{ bindSel.length }} 个</span>
        <el-button @click="bindDlg = false">取消</el-button>
        <el-button type="primary" :loading="binding" :disabled="!bindSel.length" @click="doBind">绑定</el-button>
      </template>
    </el-drawer>

    <!-- 层内表清单（画像钻取） -->
    <el-drawer v-model="ltDlg" :title="`表清单 - ${ltLayer?.code || ''} 层（${ltLayer?.name || ''}）`" size="860px" destroy-on-close>
      <div class="dl-toolbar" style="padding:0;margin-bottom:10px">
        <el-input v-model="ltKw" placeholder="表名检索" size="small" clearable style="width:200px" />
        <span class="muted">共 {{ ltFiltered.length }} 张表 · {{ fmtNum(ltTotalRows) }} 行</span>
      </div>
      <el-table :data="ltFiltered" size="small" stripe border v-loading="ltLoading" max-height="560">
        <el-table-column label="表名" min-width="200">
          <template #default="{ row }"><code class="lt-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="行数" width="100" align="right">
          <template #default="{ row }"><b>{{ fmtNum(row.rows_cnt) }}</b></template>
        </el-table-column>
        <el-table-column label="大小" width="90" align="right">
          <template #default="{ row }">{{ fmtSize(row.size_bytes) }}</template>
        </el-table-column>
        <el-table-column prop="comment" label="注释" min-width="140" show-overflow-tooltip>
          <template #default="{ row }"><span v-if="row.comment">{{ row.comment }}</span><span v-else class="muted">—</span></template>
        </el-table-column>
        <el-table-column label="最近更新" width="150">
          <template #default="{ row }">{{ fmtTime(row.last_update) }}</template>
        </el-table-column>
        <el-table-column label="元数据采集" width="150">
          <template #default="{ row }">{{ fmtTime(row.synced_time) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="!ltLoading && !ltFiltered.length" class="empty-tip muted">该层暂无物理表</div>
    </el-drawer>

    <!-- 主题域编辑 -->
    <el-drawer v-model="subjectDlg" :title="subjectForm.id ? '编辑主题域' : '新增主题域'" size="560px">
      <el-form :model="subjectForm" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="subjectForm.code" placeholder="trade" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="subjectForm.name" placeholder="交易域" /></el-form-item>
        <el-form-item label="父节点"><el-tree-select v-model="subjectForm.parent_id" :data="subjectTreeData" node-key="id" check-strictly :render-after-expand="false" style="width:100%" placeholder="无（根节点）" clearable /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="subjectForm.sort" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="subjectDlg = false">取消</el-button><el-button type="primary" @click="saveSubject">保存</el-button></template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Folder, Edit, Delete, PieChart, Histogram, InfoFilled } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
import { VChart } from '@/echarts'
import { theme } from '@/theme'

const tab = ref('layer')
const loading = ref(false)
const layers = ref<any[]>([])
const dsList = ref<any[]>([])
// 内部存储型数据源（可作数仓存储），与后端 DataSourceController.INTERNAL_TYPES 对齐
const INTERNAL_TYPES = new Set(['mysql', 'starrocks', 'doris', 'clickhouse', 'hive', 'iceberg'])

// ===== 左树：分层 =====
const treeRef = ref<any>()
const curLayer = ref('')   // ''=全部层级
const layerTree = computed(() => [
  { code: '', name: '全部层级', children: layers.value.map((l: any) => ({ ...l, children: undefined })) }
])
function onPickLayer(data: any) { curLayer.value = data.code || ''; bindPage.page = 1 }
function isInternal(t?: string) { return !!t && INTERNAL_TYPES.has(t) }

// ===== 绑定关系（一接口全拉，富化行） =====
const bindRows = ref<any[]>([])
const bindCountMap = computed<Record<string, number>>(() => {
  const m: Record<string, number> = {}
  bindRows.value.forEach(b => { m[b.layer_code] = (m[b.layer_code] || 0) + 1 })
  return m
})
function bindCount(code: string) { return bindCountMap.value[code] || 0 }

// 右表筛选 + 客户端分页
const bindKw = ref(''); const bindType = ref(''); const bindStatus = ref('')
const bindPage = reactive({ page: 1, size: 10 })
const bindTypeOpts = computed(() => [...new Set(bindRows.value.map((b: any) => b.ds_type).filter(Boolean))].sort())
const bindFiltered = computed(() => {
  let rows = curLayer.value ? bindRows.value.filter(b => b.layer_code === curLayer.value) : bindRows.value
  if (bindKw.value) {
    const k = bindKw.value.toLowerCase()
    rows = rows.filter((b: any) => (b.ds_name || '').toLowerCase().includes(k) || String(b.datasource_id).includes(k))
  }
  if (bindType.value) rows = rows.filter((b: any) => b.ds_type === bindType.value)
  if (bindStatus.value) rows = rows.filter((b: any) => b.ds_status === bindStatus.value)
  return rows
})
const bindPaged = computed(() => bindFiltered.value.slice((bindPage.page - 1) * bindPage.size, bindPage.page * bindPage.size))
function resetBindQuery() { bindKw.value = ''; bindType.value = ''; bindStatus.value = ''; bindPage.page = 1 }
function onBindSizeChange(s: number) { bindPage.size = s; bindPage.page = 1 }
function onBindPageChange(p: number) { bindPage.page = p }

async function load() {
  loading.value = true
  try {
    const [ls, ds, binds] = await Promise.all([api.govLayers(), api.daSources(), api.govLayerDs()])
    layers.value = ls; dsList.value = ds; bindRows.value = binds || []
    await nextTick()
    treeRef.value?.setCurrentKey(curLayer.value)
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}

// ===== 层级 CRUD =====
const layerDlg = ref(false); const layerForm = reactive<any>({ code: '', name: '', sort: 1, naming_pattern: '' })
function openLayer(row?: any) { Object.assign(layerForm, { code: '', name: '', sort: 1, naming_pattern: '' }, row || {}); layerDlg.value = true }
async function saveLayer() {
  try { await api.govSaveLayer({ ...layerForm }); ElMessage.success('保存成功'); layerDlg.value = false; await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function delLayer(row: any) {
  try { await ElMessageBox.confirm(`删除层级 ${row.code}？其 ${bindCount(row.code)} 条数据源绑定将一并解除`, '提示', { type: 'warning' }) } catch { return }
  try { await api.govDeleteLayer(row.code); ElMessage.success('已删除'); if (curLayer.value === row.code) curLayer.value = ''; await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== 绑定 / 解绑 =====
const bindDlg = ref(false); const bindPickKw = ref(''); const bindSel = ref<any[]>([]); const binding = ref(false)
const boundIds = computed(() => bindRows.value.filter(b => b.layer_code === curLayer.value).map(b => b.datasource_id))
const bindCandidates = computed(() => {
  if (!bindPickKw.value) return dsList.value
  const k = bindPickKw.value.toLowerCase()
  return dsList.value.filter((d: any) => (d.name || '').toLowerCase().includes(k) || (d.type || '').toLowerCase().includes(k))
})
function onBindSelChange(sel: any[]) { bindSel.value = sel }
function canPickDs(row: any) { return !boundIds.value.includes(row.id) }
function openBind() { if (!curLayer.value) return ElMessage.warning('请先在左侧选择具体层级'); bindPickKw.value = ''; bindSel.value = []; bindDlg.value = true }
async function doBind() {
  binding.value = true
  try {
    const r: any = await api.govBindLayerDs({ layer_code: curLayer.value, datasource_ids: bindSel.value.map((s: any) => s.id) })
    ElMessage.success(`已绑定 ${r.added ?? bindSel.value.length} 个数据源`)
    bindDlg.value = false; await load()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { binding.value = false }
}
async function unbind(row: any) {
  try { await ElMessageBox.confirm(`解除层级 ${row.layer_code} 与数据源「${row.ds_name || row.datasource_id}」的绑定？`, '提示', { type: 'warning' }) } catch { return }
  try { await api.govUnbindLayerDs(row.id); ElMessage.success('已解绑'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== 分层画像 / 命名巡检 =====
const stats = ref<any[]>([]); const loadingStats = ref(false)
const naming = ref<any>(null); const loadingNaming = ref(false)
function fmtNum(n: any) { const v = Number(n) || 0; return v >= 10000000 ? (v / 10000000).toFixed(1) + ' 千万' : v >= 10000 ? (v / 10000).toFixed(1) + ' 万' : String(v) }
function fmtSize(n: any) {
  const v = Number(n) || 0
  if (!v) return '—'
  if (v >= 1073741824) return (v / 1073741824).toFixed(1) + ' GB'
  if (v >= 1048576) return (v / 1048576).toFixed(1) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}
function fmtShort(s?: string) { if (!s) return '—'; const t = String(s).replace('T', ' '); return t.length >= 16 ? t.slice(5, 16) : t }
function fmtTime(s?: string) { return s ? String(s).replace('T', ' ').slice(0, 19) : '—' }

const statTotalTables = computed(() => stats.value.reduce((s, x) => s + Number(x.tables || 0), 0))
const statTotalRows = computed(() => stats.value.reduce((s, x) => s + Number(x.rows || 0), 0))
const pieOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} 张 ({d}%)' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8 },
  series: [{
    type: 'pie', radius: ['42%', '68%'], center: ['50%', '44%'],
    itemStyle: { borderRadius: 4, borderColor: 'transparent', borderWidth: 2 },
    label: { formatter: '{b}\n{c} 张' },
    data: stats.value.map((s: any) => ({ name: s.code, value: Number(s.tables || 0) }))
  }]
}))
const barOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: (v: number) => fmtNum(v) },
  grid: { left: 8, right: 18, top: 14, bottom: 6, containLabel: true },
  xAxis: { type: 'value', axisLabel: { formatter: (v: number) => fmtNum(v) } },
  yAxis: { type: 'category', data: stats.value.map((s: any) => s.code).reverse(), axisTick: { show: false } },
  series: [{
    type: 'bar', barWidth: 14, itemStyle: { borderRadius: [0, 4, 4, 0] },
    label: { show: true, position: 'right', formatter: (p: any) => fmtNum(p.value), fontSize: 11 },
    data: stats.value.map((s: any) => Number(s.rows || 0)).reverse()
  }]
}))
async function loadStats() { loadingStats.value = true; try { stats.value = await api.govLayerStats() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loadingStats.value = false } }
async function runNamingCheck() { loadingNaming.value = true; try { naming.value = await api.govLayerNamingCheck() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loadingNaming.value = false } }

// ===== 层内表清单（画像钻取） =====
const ltDlg = ref(false); const ltLoading = ref(false)
const ltLayer = ref<any>(null); const ltRows = ref<any[]>([]); const ltKw = ref('')
const ltFiltered = computed(() => {
  if (!ltKw.value) return ltRows.value
  const k = ltKw.value.toLowerCase()
  return ltRows.value.filter((r: any) => (r.name || '').toLowerCase().includes(k) || (r.comment || '').includes(ltKw.value))
})
const ltTotalRows = computed(() => ltFiltered.value.reduce((s, r) => s + Number(r.rows_cnt || 0), 0))
async function openLayerTables(s: any) {
  ltLayer.value = s; ltKw.value = ''; ltRows.value = []; ltDlg.value = true; ltLoading.value = true
  try { ltRows.value = await api.govLayerTables(s.code) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { ltLoading.value = false }
}

// ===== 主题域 =====
const subjects = ref<any[]>([])
const subjectDlg = ref(false); const subjectForm = reactive<any>({ id: null, code: '', name: '', parent_id: 0, sort: 1 })
const subjectTreeData = computed(() => subjects.value.map((s: any) => ({ ...s, value: s.id, label: s.code + ' / ' + s.name })))
const subjectPage = reactive({ page: 1, size: 10 })
const subjectPaged = computed(() => subjects.value.slice((subjectPage.page - 1) * subjectPage.size, subjectPage.page * subjectPage.size))
function onSubjectSizeChange(s: number) { subjectPage.size = s; subjectPage.page = 1 }
function onSubjectPageChange(p: number) { subjectPage.page = p }
async function loadSubjects() { try { subjects.value = await api.govSubjects() } catch { subjects.value = [] } }
function openSubject(row?: any, parent?: any) {
  Object.assign(subjectForm, { id: null, code: '', name: '', parent_id: 0, sort: 1 })
  if (row) Object.assign(subjectForm, { id: row.id, code: row.code, name: row.name, parent_id: row.parent_id || 0, sort: row.sort })
  if (parent) subjectForm.parent_id = parent.id
  subjectDlg.value = true
}
async function saveSubject() {
  if (!subjectForm.code || !subjectForm.name) return ElMessage.warning('填编码与名称')
  try { await api.govSaveSubject({ ...subjectForm }); ElMessage.success('保存成功'); subjectDlg.value = false; await loadSubjects() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function delSubject(row: any) {
  await ElMessageBox.confirm(`删除主题域 ${row.code}？`, '提示', { type: 'warning' })
  try { await api.govDeleteSubject(row.id); ElMessage.success('已删除'); await loadSubjects() } catch (e: any) { ElMessage.error(errMsg(e)) }
}

onMounted(() => { load(); loadStats(); runNamingCheck(); loadSubjects() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }

/* 左树右表布局 */
.layer-layout { display: flex; gap: 14px; min-height: 420px; }
.layer-tree-pane { width: 250px; flex-shrink: 0; border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 10px; }
.tree-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; padding: 0 2px; }
.th-t { font-weight: 600; }
.tree-hint { margin-top: 10px; padding-top: 8px; border-top: 1px dashed var(--tech-panel-border); line-height: 1.6; }
.layer-main-pane { flex: 1; min-width: 0; }

/* 树节点：label + 绑定数 badge + hover 操作 */
.tree-node { flex: 1; display: flex; align-items: center; justify-content: space-between; min-width: 0; padding-right: 4px; }
.tn-label { display: inline-flex; align-items: center; gap: 5px; min-width: 0; overflow: hidden; }
.tn-ic { color: var(--tech-primary); flex-shrink: 0; }
.tn-label b { flex-shrink: 0; }
.tn-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12.5px; color: var(--tech-text-muted); }
.tn-right { display: inline-flex; align-items: center; gap: 5px; }
.tn-badge { min-width: 18px; text-align: center; font-size: 11px; border-radius: 9px; padding: 1px 5px; background: color-mix(in srgb, var(--tech-primary) 16%, transparent); color: var(--tech-primary); font-weight: 600; }
.tn-badge.zero { background: transparent; color: var(--tech-text-muted); border: 1px dashed var(--tech-panel-border); font-weight: 400; }
.tn-ops { display: none; gap: 4px; }
.tree-node:hover .tn-ops { display: inline-flex; }
.tn-ops .el-icon { font-size: 13px; cursor: pointer; color: var(--tech-text-muted); }
.tn-ops .el-icon:hover { color: var(--tech-primary); }
.tn-ops .tn-danger:hover { color: var(--tech-danger); }

/* 右表 */
.ds-id { margin-left: 6px; }
.addr { font-size: 12px; }
.bind-to { margin-left: 4px; }
.empty-tip { padding: 18px 8px; text-align: center; }

.stat-card { border: 1px solid var(--tech-panel-border); border-radius: 6px; padding: 12px 14px; }
.stat-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.stat-head b { font-size: 15px; }
.stat-head .el-tag { margin-left: auto; }
.stat-row { display: flex; justify-content: space-between; align-items: center; font-size: 13px; line-height: 24px; }
.unit { font-size: 11px; color: var(--tech-text-muted); margin-left: 2px; font-weight: 400; }
.stat-src { margin-top: 6px; font-size: 11px; }

/* 画像：图表行 + 可点击卡片 */
.stats-charts { display: flex; gap: 10px; margin-bottom: 12px; }
.chart-panel { flex: 1; min-width: 0; border: 1px solid var(--tech-panel-border); border-radius: 6px; padding: 10px 12px; }
.cp-t { display: flex; align-items: center; gap: 6px; font-weight: 600; margin-bottom: 4px; }
.cp-t .el-icon { color: var(--tech-primary); }
.cp-t .muted { margin-left: auto; font-weight: 400; }
.chart-panel .ch { height: 210px; }
.stat-click { cursor: pointer; transition: border-color .15s ease, box-shadow .15s ease; }
.stat-click:hover { border-color: color-mix(in srgb, var(--tech-primary) 45%, transparent); box-shadow: 0 2px 12px rgba(0, 0, 0, .12); }
.stat-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; padding-top: 6px; border-top: 1px dashed var(--tech-panel-border); font-size: 11.5px; }
.stat-foot .link { color: var(--tech-primary); }
.stat-click:hover .link { text-decoration: underline; }
.lt-name { font-size: 12.5px; }
.suggest { color: var(--el-color-success); font-family: monospace; }
</style>
