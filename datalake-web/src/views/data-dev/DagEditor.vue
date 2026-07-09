<template>
  <div class="dag-wrap">
    <div class="dag-panel">
      <div class="dag-panel-title">节点（拖到画布）</div>
      <div v-for="(d, idx) in defs" :key="idx" class="dag-node-tpl" draggable="true" @dragstart="onDragStart($event, d)">{{ d.label }}</div>
      <div class="dag-tip">拖出节点后，悬停节点→拖锚点连线</div>
    </div>
    <div class="dag-canvas" @drop="onDrop" @dragover.prevent>
      <VueFlow :nodes="nodes" :edges="edges" :fit-view-on-init="true" @connect="onConnect" @node-click="onNodeClick" @edge-click="onEdgeClick">
        <Background :gap="16" pattern-color="#3a4a5a" />
        <Controls />
        <MiniMap />
      </VueFlow>
    </div>
    <div class="dag-prop">
      <div class="dag-panel-title">{{ selectedLabel }}</div>
      <div v-if="!selected" class="dag-tip">点击节点编辑属性；悬停节点可拖出连线。</div>
      <template v-else>
        <div class="prop-row"><label>节点名称</label><el-input v-model="selected.data.label" size="small" /></div>
        <div class="prop-row"><label>节点类型</label><span class="dag-tip">{{ selected.data.type }} / {{ selected.data.kind }}</span></div>
        <div class="prop-row"><label>配置 (JSON)</label>
          <el-input v-model="configText" type="textarea" :rows="10" size="small" style="font-family:monospace" placeholder='{"tableName":"ods.t","fields":["id","name"]}' />
          <div class="dag-tip" v-if="configTip">{{ configTip }}</div>
        </div>
        <el-button size="small" type="danger" @click="removeSelected">删除节点</el-button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

const props = defineProps<{ modelValue?: string; jobType?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const NODE_DEFS: Record<string, any[]> = {
  flink_dag: [
    { type: 'source', kind: 'table', label: '表数据源', cfg: '{ tableName: "ods.ods_t", fields: ["id","name"] }' },
    { type: 'source', kind: 'kafka', label: 'Kafka源', cfg: '{ topic: "t", fields: ["k","v"] }' },
    { type: 'operator', kind: 'filter', label: '过滤', cfg: '{ expression: "id > 0" }' },
    { type: 'operator', kind: 'aggregate', label: '聚合', cfg: '{ groupKey: "id", agg: "COUNT(*)" }' },
    { type: 'operator', kind: 'join', label: 'JOIN', cfg: '{}' },
    { type: 'sink', kind: 'table', label: '写表', cfg: '{ tableName: "dwd.dwd_t" }' },
    { type: 'sink', kind: 'kafka', label: '写Kafka', cfg: '{ topic: "out" }' },
  ],
  kettle_hop: [
    { type: 'hop', kind: 'input', label: '输入(表)', cfg: '{ tableName: "ods.t" }' },
    { type: 'hop', kind: 'transform', label: '转换', cfg: '{}' },
    { type: 'hop', kind: 'output', label: '输出(表)', cfg: '{ tableName: "dwd.t" }' },
  ],
}
const defs = computed(() => NODE_DEFS[props.jobType || 'flink_dag'] || [])

const { addEdges, screenToFlowCoordinate } = useVueFlow()
const nodes = ref<any[]>([])
const edges = ref<any[]>([])
const selected = ref<any>(null)
const configText = ref('{}')
const configTip = ref('')

watch(() => props.modelValue, (v) => {
  if (!v) { nodes.value = []; edges.value = []; return }
  try {
    const dag = JSON.parse(v)
    nodes.value = (dag.nodes || []).map((n: any) => ({ id: n.id, type: 'default', position: n.position, data: n.data }))
    edges.value = (dag.edges || []).map((e: any) => ({ id: e.id, source: e.source, target: e.target }))
  } catch { /* ignore */ }
}, { immediate: true })

watch(configText, (t) => {
  if (!selected.value) return
  try { selected.value.data.config = JSON.parse(t || '{}'); configTip.value = '' }
  catch { configTip.value = 'JSON 格式有误，未保存' }
})

watch([nodes, edges], () => emitChange(), { deep: true })

const selectedLabel = computed(() => selected.value ? `属性 - ${selected.value.data.label}` : '属性')

function onDragStart(e: DragEvent, d: any) {
  e.dataTransfer?.setData('application/vueflow', JSON.stringify(d))
  e.dataTransfer!.effectAllowed = 'move'
}
function onDrop(e: DragEvent) {
  const raw = e.dataTransfer?.getData('application/vueflow')
  if (!raw) return
  const d = JSON.parse(raw)
  const position = screenToFlowCoordinate({ x: e.clientX, y: e.clientY })
  const id = `n_${Date.now()}`
  let cfg: any = {}
  try { cfg = JSON.parse(d.cfg?.replace(/(\w+):/g, '"$1":') || '{}') } catch { /* */ }
  nodes.value = [...nodes.value, { id, type: 'default', position, data: { label: d.label, type: d.type, kind: d.kind, config: cfg } }]
}
function onConnect(params: any) { addEdges(params) }
function onNodeClick({ node }: any) {
  selected.value = node
  configText.value = JSON.stringify(node.data.config || {}, null, 2)
}
function onEdgeClick() { selected.value = null }
function removeSelected() {
  if (!selected.value) return
  const id = selected.value.id
  nodes.value = nodes.value.filter((n) => n.id !== id)
  edges.value = edges.value.filter((e) => e.source !== id && e.target !== id)
  selected.value = null
}
function emitChange() {
  const dag = {
    nodes: nodes.value.map((n: any) => ({ id: n.id, type: n.data?.type || n.type, position: n.position, data: n.data })),
    edges: edges.value.map((e: any) => ({ id: e.id, source: e.source, target: e.target })),
  }
  emit('update:modelValue', JSON.stringify(dag))
}
</script>
<style scoped>
.dag-wrap { display: flex; gap: 6px; height: 520px; border: 1px solid var(--tech-panel-border); border-radius: 4px; overflow: hidden; }
.dag-panel { width: 130px; background: rgba(0,0,0,0.25); padding: 8px; overflow-y: auto; flex-shrink: 0; }
.dag-panel-title { font-size: 12px; color: var(--tech-text-muted); margin-bottom: 6px; font-weight: 600; }
.dag-node-tpl { padding: 6px 8px; margin-bottom: 5px; background: var(--tech-panel, #1e2a3a); border: 1px solid var(--tech-panel-border); border-radius: 3px; cursor: grab; font-size: 12px; }
.dag-node-tpl:hover { border-color: var(--tech-accent, #409eff); }
.dag-canvas { flex: 1; position: relative; background: rgba(0,0,0,0.12); }
.dag-prop { width: 250px; background: rgba(0,0,0,0.25); padding: 8px; overflow-y: auto; flex-shrink: 0; }
.prop-row { margin-bottom: 8px; }
.prop-row label { display: block; font-size: 11px; color: var(--tech-text-muted); margin-bottom: 2px; }
.dag-tip { color: var(--tech-text-muted); font-size: 11px; line-height: 1.4; }
.vue-flow__node-default { font-size: 12px; }
</style>
