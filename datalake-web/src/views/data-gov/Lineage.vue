<template>
  <div class="dl-card">
    <div class="page-head">
      <div>
        <h2><el-icon><Share /></el-icon> 血缘分析</h2>
        <p>自动解析离线接入/实时/接出/离线开发(SQL·DAG·Kettle)/脚本/工作流的表级血缘。分层布局：上游居左、下游居右，中心表金色高亮；点击节点聚焦影响链路。</p>
      </div>
      <div class="head-stats" v-if="stats">
        <span>覆盖率<b>{{ stats.coverage }}%</b></span>
        <span>血缘边<b>{{ stats.edgeCount }}</b></span>
      </div>
    </div>

    <!-- 控制条 -->
    <div class="bar">
      <el-select v-model="table" filterable remote allow-create default-first-option clearable
        :remote-method="searchTables" :loading="searching" placeholder="搜索或输入表名（如 ods_order）"
        size="small" style="width:240px" @change="draw">
        <el-option v-for="t in tableOptions" :key="t" :label="t" :value="t" />
      </el-select>
      <el-radio-group v-model="direction" size="small" style="margin:0 8px">
        <el-radio-button label="up">上游</el-radio-button>
        <el-radio-button label="both">双向</el-radio-button>
        <el-radio-button label="down">下游</el-radio-button>
      </el-radio-group>
      <span class="hint">深度</span>
      <el-select v-model="depth" size="small" style="width:72px">
        <el-option :value="1" label="1 跳" /><el-option :value="2" label="2 跳" /><el-option :value="3" label="3 跳" /><el-option :value="4" label="4 跳" />
      </el-select>
      <el-button type="primary" size="small" :loading="loading" @click="draw">查询</el-button>
      <el-button size="small" :loading="rebuilding" @click="rebuild">重算血缘</el-button>
    </div>

    <!-- 图例 / 边类型筛选 -->
    <div class="legend" v-if="graphData">
      <span v-for="(c, t) in EDGE_COLOR" :key="t" class="lg" :class="{ off: hiddenTypes.has(t) }" @click="toggleType(t)">
        <span class="dot" :style="{ background: c }"></span>{{ t }}
      </span>
      <span class="lg-static"><span class="dot" :style="{ background: centerColor }"></span>中心表</span>
      <span v-if="stats" class="stats">覆盖率 {{ stats.coverage }}%（{{ stats.withLineage }}/{{ stats.totalTables }} 表 · {{ stats.edgeCount }} 边）</span>
    </div>

    <el-row :gutter="12" style="margin-top:10px">
      <!-- 图谱 -->
      <el-col :span="18">
        <div class="graph-wrap">
          <v-chart v-if="graphData && hasVisible" :option="graphOption" :theme="chartTheme" autoresize class="chart" @click="onNode" />
          <el-empty v-else :description="graphData ? '当前筛选下无可显示血缘' : '输入表名并查询，查看血缘图谱'" :image-size="80" />
        </div>
      </el-col>
      <!-- 侧栏：节点详情 + 字段下钻 -->
      <el-col :span="6">
        <div class="side">
          <template v-if="selected">
            <div class="side-title">{{ selected.label }}</div>
            <el-tag size="small" type="info" effect="dark" style="margin-bottom:8px">{{ selected.category === 'external' ? '外部' : '库表' }}</el-tag>
            <div class="kv"><span>上游</span><b>{{ degreeOf(selected.id, true) }}</b></div>
            <div class="kv"><span>下游</span><b>{{ degreeOf(selected.id, false) }}</b></div>
            <div class="btns">
              <el-button size="small" @click="recenter(selected.id)">以此为中心</el-button>
              <el-button size="small" @click="impactOf(selected.id)">影响分析</el-button>
              <el-button size="small" type="primary" plain :disabled="!curMetaId" @click="fieldDlg = true">字段血缘</el-button>
            </div>
          </template>
          <div v-else class="side-empty">点击图谱节点查看详情<br/>并聚焦其影响链路</div>
        </div>
      </el-col>
    </el-row>

    <!-- 字段血缘抽屉 -->
    <el-drawer v-model="fieldDlg" :title="'字段血缘 · ' + (selected?.label || '')" size="44%">
      <div class="hint" style="margin-bottom:6px">字段（点击行查看来源映射）</div>
      <el-table :data="fields" size="small" border max-height="220" highlight-current-row @row-click="pickField">
        <el-table-column prop="name" label="字段" min-width="140" />
        <el-table-column prop="type" label="类型" width="130" />
        <el-table-column prop="comment" label="注释" show-overflow-tooltip />
      </el-table>
      <div class="hint" style="margin:12px 0 6px">来源映射</div>
      <el-empty v-if="!fieldMappings.length" description="点击字段查看（或该字段暂无映射，可在数据地图详情配置）" :image-size="60" />
      <el-table v-else :data="fieldMappings" size="small" border>
        <el-table-column prop="src_table" label="源表" min-width="140" />
        <el-table-column prop="src_field" label="源字段" width="120" />
        <el-table-column prop="logical_field" label="→ 目标字段" width="120" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Share } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { api, errMsg } from '@/api'
import { theme } from '@/theme'

const chartTheme = theme.chartTheme
const isDark = theme.isDark
const table = ref('')
const tableOptions = ref<string[]>([])
const searching = ref(false)
const direction = ref('both')
const depth = ref(2)
const loading = ref(false)
const rebuilding = ref(false)
const graphData = ref<any>(null)
const stats = ref<any>(null)
const selectedId = ref('')
const hiddenTypes = ref<Set<string>>(new Set())
// 字段下钻
const fieldDlg = ref(false)
const curMetaId = ref(0)
const fields = ref<any[]>([])
const fieldMappings = ref<any[]>([])

// 主题感知配色：浅色 DIFY 柔和靛蓝/橙；暗色霓虹青/金
const C = computed(() => isDark.value ? {
  ink: '#d6e6ff', muted: '#aebfe2', border: 'rgba(255,255,255,0.25)', glow: 'rgba(255,214,102,0.7)',
  center: '#ffd666', table: '#00e0ff', external: '#ffb020',
  eOffline: '#2ee6a6', eStream: '#7c5cff', eWorkflow: '#ff8c42'
} : {
  ink: '#101828', muted: '#667085', border: '#d0d5dd', glow: 'rgba(21,87,239,0.25)',
  center: '#1557ef', table: '#4f46e5', external: '#f79009',
  eOffline: '#16b364', eStream: '#7c5cff', eWorkflow: '#f04438'
})
const CAT_COLOR = computed<Record<string, string>>(() => ({ table: C.value.table, external: C.value.external, ds: C.value.table }))
const EDGE_COLOR = computed<Record<string, string>>(() => ({
  OFFLINE: C.value.eOffline, STREAM: C.value.eStream, EXPORT: C.value.external,
  DEV_JDBC_SQL: C.value.eOffline, DEV_FLINK_SQL: C.value.eStream, DEV_FLINK_DAG: C.value.eStream, DEV_KETTLE_HOP: C.value.eStream,
  DEV_SCRIPT: C.value.eOffline, DEV_WORKFLOW: C.value.eWorkflow
}))
const centerColor = computed(() => C.value.center)

const centerId = computed(() => (table.value || '').toLowerCase())
const selected = computed(() => (graphData.value?.nodes || []).find((n: any) => n.id === selectedId.value) || null)
const hasVisible = computed(() => (graphData.value?.links || []).some((l: any) => !hiddenTypes.value.has(l.edgeType)) || (graphData.value?.nodes?.length > 0))

/** 分层布局：center=0，下游 +1（右），上游 -1（左）；同层竖向堆叠。返回带 x/y 的节点。 */
function layout(nodes: any[], links: any[], cId: string) {
  const out = new Map<string, string[]>()   // id -> 下游
  const inn = new Map<string, string[]>()   // id -> 上游
  for (const l of links) {
    (out.get(l.source) || out.set(l.source, []).get(l.source)!).push(l.target)
    ;(inn.get(l.target) || inn.set(l.target, []).get(l.target)!).push(l.source)
  }
  const lvl = new Map<string, number>()
  lvl.set(cId, 0)
  // 下游
  let front = [cId], lv = 0
  while (front.length && lv < 9) { lv++; const nx: string[] = []; for (const id of front) for (const d of out.get(id) || []) if (!lvl.has(d)) { lvl.set(d, lv); nx.push(d) } front = nx }
  // 上游
  front = [cId]; lv = 0
  while (front.length && lv > -9) { lv--; const nx: string[] = []; for (const id of front) for (const u of inn.get(id) || []) if (!lvl.has(u)) { lvl.set(u, lv); nx.push(u) } front = nx }
  // 按层分组 → 坐标
  const byLv = new Map<number, string[]>()
  for (const n of nodes) {
    const l = lvl.has(n.id) ? lvl.get(n.id)! : 0
    ;(byLv.get(l) || byLv.set(l, []).get(l)!).push(n.id)
  }
  const pos = new Map<string, { x: number; y: number }>()
  const xStep = 230, yStep = 74
  for (const [l, ids] of byLv) {
    const cnt = ids.length
    ids.forEach((id, i) => pos.set(id, { x: l * xStep, y: (i - (cnt - 1) / 2) * yStep }))
  }
  return nodes.map(n => ({ ...n, x: pos.get(n.id)?.x ?? 0, y: pos.get(n.id)?.y ?? 0 }))
}

/** 从 id 出发双向可达集合（用于点击聚焦高亮）。 */
function reachable(links: any[], id: string): Set<string> {
  const adj = new Map<string, string[]>()
  for (const l of links) { (adj.get(l.source) || adj.set(l.source, []).get(l.source)!).push(l.target); (adj.get(l.target) || adj.set(l.target, []).get(l.target)!).push(l.source) }
  const set = new Set<string>([id])
  let front = [id]
  while (front.length) { const nx: string[] = []; for (const x of front) for (const y of adj.get(x) || []) if (!set.has(y)) { set.add(y); nx.push(y) } front = nx }
  return set
}

/** id 的直接上游/下游度数。up=true 数上游。 */
function degreeOf(id: string, up: boolean): number {
  const links = graphData.value?.links || []
  return up ? links.filter((l: any) => l.target === id).length : links.filter((l: any) => l.source === id).length
}

const graphOption = computed(() => {
  const g = graphData.value
  if (!g) return {}
  const c = C.value, cat = CAT_COLOR.value, edge = EDGE_COLOR.value
  const cId = centerId.value
  const positioned = layout(g.nodes || [], g.links || [], cId)
  const reach = selectedId.value ? reachable(g.links || [], selectedId.value) : null
  const data = positioned.map((n: any) => {
    const isCenter = n.id === cId
    const inReach = !reach || reach.has(n.id)
    return {
      id: n.id, name: n.label, x: n.x, y: n.y,
      symbolSize: isCenter ? 56 : (n.category === 'external' ? 26 : 38),
      itemStyle: {
        color: isCenter ? c.center : (cat[n.category] || c.muted),
        borderColor: isCenter ? c.center : c.border,
        borderWidth: isCenter ? 2 : 1,
        opacity: inReach ? 1 : 0.12,
        shadowBlur: isCenter && isDark.value ? 18 : 0,
        shadowColor: c.glow
      },
      label: { show: true, color: isCenter ? c.ink : c.muted, position: isCenter ? 'bottom' : 'right', fontSize: isCenter ? 13 : 11, fontWeight: isCenter ? 'bold' : 'normal' }
    }
  })
  const links = (g.links || []).filter((l: any) => !hiddenTypes.value.has(l.edgeType)).map((l: any) => {
    const inReach = !reach || (reach.has(l.source) && reach.has(l.target))
    return {
      source: l.source, target: l.target,
      lineStyle: { color: edge[l.edgeType] || c.muted, curveness: 0.18, opacity: inReach ? 0.85 : 0.06, width: inReach ? 2 : 1 },
      value: (l.jobName || '') + ' · ' + (l.edgeType || '')
    }
  })
  return {
    tooltip: { formatter: (p: any) => (p.dataType === 'edge' ? (p.data.value || '') : p.data.name) },
    series: [{
      type: 'graph', layout: 'none', roam: true, draggable: true,
      label: { show: true, position: 'right', fontSize: 11, color: c.muted },
      edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 9,
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
      data, links
    }]
  }
})

async function searchTables(q: string) {
  if (!q) { tableOptions.value = []; return }
  searching.value = true
  try { const rows: any[] = await api.govMetaList({ kw: q }); tableOptions.value = rows.map((r: any) => r.table_name).filter(Boolean) }
  catch { tableOptions.value = [] } finally { searching.value = false }
}
async function draw() {
  if (!table.value.trim()) { ElMessage.warning('请输入或选择表名'); return }
  loading.value = true
  selectedId.value = ''
  try {
    graphData.value = await api.govLineageGraph(table.value.trim(), '', 0, direction.value, depth.value)
    if (!(graphData.value?.nodes?.length)) ElMessage.info('未找到血缘关系')
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function toggleType(t: string) {
  const s = new Set(hiddenTypes.value)
  s.has(t) ? s.delete(t) : s.add(t)
  hiddenTypes.value = s
}
async function onNode(p: any) {
  if (p?.dataType !== 'node') return
  selectedId.value = p.data.id || p.data.name
  // 反查元数据以支持字段下钻
  fields.value = []; fieldMappings.value = []; curMetaId.value = 0
  try {
    const list: any[] = await api.govMetaList({ kw: selectedId.value })
    const hit = list.find((r: any) => String(r.table_name).toLowerCase() === selectedId.value.toLowerCase())
    if (hit) { curMetaId.value = hit.id; const d: any = await api.govMetaDetail(hit.id); fields.value = d.columns_json ? JSON.parse(d.columns_json) : [] }
  } catch { /* 无元数据详情也能继续看图谱 */ }
}
function recenter(id: string) { table.value = id; direction.value = 'both'; draw() }
function impactOf(id: string) { table.value = id; direction.value = 'down'; depth.value = Math.max(depth.value, 3); draw() }
async function pickField(f: any) {
  if (!curMetaId.value) { ElMessage.info('该表无元数据详情'); return }
  try { const r: any = await api.govLineageField(curMetaId.value, f.name); fieldMappings.value = r.mappings || [] }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function loadStats() { try { stats.value = await api.govLineageStats() } catch { /* */ } }
async function rebuild() {
  rebuilding.value = true
  try { const r: any = await api.govLineageRebuild(); ElMessage.success(`血缘已重建（${r.edges} 条边）`); await loadStats(); if (table.value) await draw() }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { rebuilding.value = false }
}
onMounted(() => { loadStats() })
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 18px; }
.page-head h2 { margin: 0; font-size: 18px; font-weight: 600; color: var(--tech-text); display: flex; align-items: center; gap: 8px; }
.page-head h2 .el-icon { color: var(--tech-primary); }
.page-head p { margin: 6px 0 0; color: var(--tech-text-muted); font-size: 13px; }
.head-stats { display: flex; gap: 22px; }
.head-stats span { font-size: 13px; color: var(--tech-text-muted); }
.head-stats b { color: var(--tech-text); font-size: 15px; font-weight: 600; margin-left: 4px; }
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { color: var(--tech-text-muted); font-size: 13px; }
.bar { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.legend { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; padding: 10px 14px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); }
.lg { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--tech-text-muted); cursor: pointer; user-select: none; transition: color .15s; }
.lg:hover { color: var(--tech-text); }
.lg.off { opacity: 0.35; text-decoration: line-through; }
.lg-static { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--tech-text-muted); }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.stats { margin-left: auto; color: var(--tech-text-muted); font-size: 12px; }
.graph-wrap { border: 1px solid var(--tech-panel-border); border-radius: 10px; background: var(--tech-panel); box-shadow: var(--tech-shadow); }
.chart { width: 100%; height: calc(100vh - 230px); min-height: 460px; }
.side { padding: 14px; min-height: 200px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); }
.side-title { font-size: 14px; font-weight: 600; color: var(--tech-text); word-break: break-all; margin-bottom: 8px; }
.kv { display: flex; justify-content: space-between; font-size: 13px; color: var(--tech-text-muted); padding: 3px 0; }
.kv b { color: var(--tech-text); }
.btns { display: flex; flex-direction: column; gap: 6px; margin-top: 10px; }
.side-empty { color: var(--tech-text-muted); font-size: 13px; text-align: center; padding: 40px 0; line-height: 1.8; }
</style>
