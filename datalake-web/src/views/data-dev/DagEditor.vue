<template>
  <div class="dag-wrap">
    <!-- 左：节点面板（按 group 分组，拖到画布） -->
    <div class="dag-panel">
      <div class="dag-panel-title">
        <span>节点面板</span>
        <span class="dag-panel-sub">拖到画布</span>
      </div>
      <div v-for="g in groups" :key="g" class="dag-cat">
        <div class="dag-cat-title"><i class="cat-dot" :style="{ background: GROUP_COLOR[g] }"></i>{{ GROUP_LABEL[g] }}</div>
        <div v-for="d in nodesOf(g)" :key="d.kind" class="dag-node-tpl" draggable="true"
             @dragstart="onDragStart($event, d)" @dragend="onDragEnd">
          <span class="tpl-icon" :style="{ background: GROUP_COLOR[g] + '22', color: GROUP_COLOR[g] }">{{ d.icon }}</span>
          <span class="tpl-label">{{ d.label }}</span>
          <span v-if="d.planned" class="tpl-badge">规划中</span>
        </div>
      </div>
      <div class="dag-tip">① 拖节点到画布　② 拖节点上下锚点连线　③ 双击节点编辑属性</div>
    </div>

    <!-- 中：画布 -->
    <div class="dag-canvas" @drop="onDrop" @dragover="onDragOver" @dragenter.prevent>
      <VueFlow :id="flowId" :fit-view-on-init="true" :delete-key-code="['Backspace','Delete']" :min-zoom="0.3" :max-zoom="2"
               @connect="onConnect" @node-click="onNodeClick" @pane-click="onPaneClick"
               @node-double-click="onNodeDoubleClick" @node-drag-stop="scheduleEmit" @nodes-change="onGraphChange" @edges-change="onGraphChange">
        <Background :gap="18" :size="1.2" pattern-color="#2c3e55" />
        <Controls position="bottom-right" />
        <MiniMap :node-color="miniColor" mask-color="rgba(8,14,22,0.7)" />
        <template #node-step="{ data, selected: sel }">
          <div class="step-node" :class="['cat-' + data.type, { 'step-sel': sel }]">
            <Handle type="target" :position="Position.Top" />
            <div class="step-head">
              <span class="step-icon">{{ iconFor(data) }}</span>
              <span class="step-label">{{ data.label }}</span>
              <span v-if="plannedOf(data)" class="step-planned">规划中</span>
            </div>
            <div class="step-kind"><span class="kind-tag">{{ GROUP_LABEL[groupOf(data)] || data.type }}</span> · {{ data.kind }}</div>
            <Handle type="source" :position="Position.Bottom" />
          </div>
        </template>
      </VueFlow>
      <div v-if="empty" class="dag-empty">从左侧拖入节点开始编排</div>
    </div>

    <!-- 右：属性面板 -->
    <div class="dag-prop">
      <div class="dag-panel-title">{{ selected ? selected.data.label : '属性配置' }}</div>
      <div v-if="!selected" class="dag-prop-empty">点击画布节点编辑属性</div>
      <template v-else>
        <div class="prop-row"><label>节点名称</label><el-input v-model="selected.data.label" size="small" @input="scheduleEmit" /></div>
        <div class="prop-row"><label>类别</label>
          <div class="prop-cat"><i class="cat-dot" :style="{ background: CAT_COLOR[selected.data.type] }"></i>{{ GROUP_LABEL[groupOf(selected.data)] || selected.data.type }} · {{ selected.data.kind }}</div>
        </div>
        <!-- complex 节点：右侧只给入口，详细配置走双击 dialog -->
        <div v-if="selectedDef?.complex" class="prop-complex">
          <el-button size="small" type="primary" plain @click="openDialog(selected)">详细配置</el-button>
          <div class="prop-complex-hint">双击节点或点此按钮打开详细配置</div>
          <div class="prop-complex-info">
            <span v-if="selected.data.config?.tableName">表: {{ selected.data.config.tableName }}</span>
            <span v-if="(selected.data.config?.fields||[]).length">{{ (selected.data.config.fields||[]).length }} 字段</span>
          </div>
        </div>
        <!-- 简单节点：直接渲染 fields -->
        <template v-else>
          <div class="prop-row" v-for="f in fieldsOf(selected)" :key="f.key">
            <label>{{ f.label }}</label>
            <el-input v-if="f.type === 'text'" v-model="selected.data.config[f.key]" size="small" :placeholder="f.placeholder" @input="scheduleEmit" />
            <el-input v-else-if="f.type === 'textarea'" v-model="selected.data.config[f.key]" type="textarea" :rows="f.rows || 3" size="small" class="mono" :placeholder="f.placeholder" @input="scheduleEmit" />
            <el-input-number v-else-if="f.type === 'number'" v-model="selected.data.config[f.key]" size="small" :min="0" controls-position="right" class="num" @change="scheduleEmit" />
            <el-input v-else-if="f.type === 'fields'" :model-value="(selected.data.config[f.key] || []).join(', ')" @update:model-value="setFields(f.key, $event)" size="small" :placeholder="f.placeholder || '逗号分隔'" />
            <el-select v-else-if="f.type === 'select'" v-model="selected.data.config[f.key]" size="small" :placeholder="f.placeholder" filterable style="width:100%" @change="scheduleEmit">
              <el-option v-for="o in f.options || []" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-switch v-else-if="f.type === 'switch'" v-model="selected.data.config[f.key]" @change="scheduleEmit" />
          </div>
        </template>
        <el-button size="small" type="danger" plain @click="removeSelected">删除节点</el-button>
      </template>
    </div>

    <!-- 复杂节点详细配置弹窗 -->
    <NodeConfigDialog v-model="configDlg" :node="dialogNode" :fields="dialogFields" :datasources="datasources"
                      :group-label="dialogGroupLabel" :planned="dialogPlanned" @save="onDialogSave" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { VueFlow, useVueFlow, Handle, Position } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { api } from '@/api'
import NodeConfigDialog from './NodeConfigDialog.vue'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

type FieldOpt = { label: string; value: string }
type Field = {
  key: string; label: string
  type: 'text' | 'textarea' | 'number' | 'fields' | 'select' | 'switch' | 'sourceTable' | 'fieldsTable' | 'sqlEditor'
  placeholder?: string; rows?: number
  options?: FieldOpt[]; selectMode?: 'table' | 'columns'; mappingType?: 'auto' | 'key'
}
type NodeDef = {
  kind: string; category: 'source' | 'operator' | 'sink'; group: string
  label: string; icon: string; fields: Field[]
  planned?: boolean; complex?: boolean
}

const props = defineProps<{ modelValue?: string; jobType?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

// 8 类面板分组
const GROUP_LABEL: Record<string, string> = {
  input: '输入', transform: '数据转换', cleanse: '数据清洗', mask: '脱敏处理',
  query: '查询', stats: '统计', flow: '流程', output: '输出',
}
const GROUP_COLOR: Record<string, string> = {
  input: '#67c23a', transform: '#409eff', cleanse: '#e6a23c', mask: '#f56c6c',
  query: '#909399', stats: '#9b59b6', flow: '#1abc9c', output: '#d4830a',
}
// 画布节点按 category 三色着色（后端语义）
const CAT_COLOR: Record<string, string> = { source: '#67c23a', operator: '#409eff', sink: '#e6a23c' }

// —— 字段模板工厂 ——
const F = {
  tableName: (label = '表名', ph = 'ods.ods_t'): Field => ({ key: 'tableName', label, type: 'text', placeholder: ph }),
  fields: (ph = 'id, name'): Field => ({ key: 'fields', label: '字段', type: 'fields', placeholder: ph }),
  expression: (label = '过滤条件', ph = 'id > 0 AND name IS NOT NULL'): Field => ({ key: 'expression', label, type: 'textarea', placeholder: ph, rows: 3 }),
  groupKeys: (): Field => ({ key: 'groupKeys', label: '分组字段', type: 'fields', placeholder: 'id, region' }),
  aggExpr: (): Field => ({ key: 'aggExpr', label: '聚合表达式', type: 'textarea', placeholder: 'COUNT(*) AS cnt, SUM(amt) AS total', rows: 2 }),
  orderBy: (): Field => ({ key: 'orderBy', label: '排序', type: 'text', placeholder: 'id DESC, name' }),
  limit: (): Field => ({ key: 'limit', label: 'Limit', type: 'number' }),
  col: (): Field => ({ key: 'col', label: '目标列', type: 'text', placeholder: 'status' }),
  mapping: (): Field => ({ key: 'mapping', label: '映射(JSON)', type: 'textarea', placeholder: '{"A":"X","B":"Y"}', rows: 3 }),
  exprs: (): Field => ({ key: 'exprs', label: '计算表达式', type: 'textarea', placeholder: 'amt * 1.0 / total AS ratio', rows: 2 }),
  topic: (): Field => ({ key: 'topic', label: 'Topic', type: 'text', placeholder: 'pharma-orders' }),
  joinType: (): Field => ({ key: 'joinType', label: '连接类型', type: 'select', options: [{ label: 'INNER JOIN', value: 'INNER' }, { label: 'LEFT JOIN', value: 'LEFT' }, { label: 'RIGHT JOIN', value: 'RIGHT' }, { label: 'FULL JOIN', value: 'FULL' }] }),
  onExpr: (): Field => ({ key: 'onExpr', label: '连接条件', type: 'text', placeholder: 'a.id = b.aid' }),
  rule: (): Field => ({ key: 'expression', label: '规则/表达式', type: 'textarea', placeholder: '处理规则或表达式', rows: 2 }),
}
// sourceTable / fieldsTable / sqlEditor 复合字段
const sourceTable = (mode: 'table' | 'columns'): Field => ({ key: '_src', label: mode === 'columns' ? '数据源 / 表 / 字段' : '目标数据源 / 表', type: 'sourceTable', selectMode: mode })
const fieldMapping = (): Field => ({ key: 'fieldMapping', label: '字段映射', type: 'fieldsTable', mappingType: 'auto' })
const keyMapping = (): Field => ({ key: 'keyMapping', label: '关键字映射', type: 'fieldsTable', mappingType: 'key' })
const sqlEditor = (): Field => ({ key: 'sqlEditor', label: 'SQL 编辑模式', type: 'sqlEditor' })

// 后端可翻译的转换算子（filter/select/sort/aggregate/value_map/calc/dedup）
const CORE_OPS: NodeDef[] = [
  { kind: 'select', category: 'operator', group: 'transform', label: '字段选择', icon: '📋', fields: [F.fields('id, name')] },
  { kind: 'filter', category: 'operator', group: 'transform', label: '过滤记录', icon: '🔲', fields: [F.expression()] },
  { kind: 'dedup', category: 'operator', group: 'transform', label: '去除重复', icon: '⚓', fields: [F.fields('id')] },
  { kind: 'value_map', category: 'operator', group: 'transform', label: '值映射', icon: '🔀', fields: [F.col(), F.mapping()] },
  { kind: 'calc', category: 'operator', group: 'transform', label: '计算列', icon: '➗', fields: [F.exprs()] },
  { kind: 'sort', category: 'operator', group: 'transform', label: '排序', icon: '↕️', fields: [F.orderBy(), F.limit()] },
  { kind: 'aggregate', category: 'operator', group: 'stats', label: '分组聚合', icon: 'Σ', fields: [F.groupKeys(), F.aggExpr()] },
]
// 规划中算子（后端未实现翻译，运行报错）
const PLANNED_OPS: NodeDef[] = [
  { kind: 'join', category: 'operator', group: 'transform', label: '记录集连接', icon: '🔗', planned: true, complex: true, fields: [F.joinType(), F.onExpr()] },
  { kind: 'string_replace', category: 'operator', group: 'transform', label: '字符串替换', icon: '🔁', planned: true, fields: [F.col(), { key: 'from', label: '查找', type: 'text' }, { key: 'to', label: '替换为', type: 'text' }] },
  { kind: 'string_ops', category: 'operator', group: 'transform', label: '字符串操作', icon: '🔤', planned: true, fields: [F.col(), F.rule()] },
  { kind: 'split_field', category: 'operator', group: 'transform', label: '拆分字段', icon: '✂️', planned: true, fields: [F.col(), { key: 'delimiter', label: '分隔符', type: 'text', placeholder: ',' }] },
  { kind: 'string_to_date', category: 'operator', group: 'transform', label: '字符串转日期', icon: '📅', planned: true, fields: [F.col(), { key: 'format', label: '格式', type: 'text', placeholder: 'yyyy-MM-dd' }] },
  { kind: 'js_code', category: 'operator', group: 'transform', label: 'JavaScript代码', icon: '📜', planned: true, fields: [{ key: 'expression', label: 'JS脚本', type: 'textarea', rows: 4 }] },
  { kind: 'java_code', category: 'operator', group: 'transform', label: 'Java代码', icon: '☕', planned: true, fields: [{ key: 'expression', label: 'Java代码', type: 'textarea', rows: 4 }] },
  { kind: 'exec_sql', category: 'operator', group: 'transform', label: '执行SQL脚本', icon: '🗄️', planned: true, fields: [{ key: 'expression', label: 'SQL', type: 'textarea', rows: 4 }] },
  { kind: 'num_range', category: 'operator', group: 'cleanse', label: '数值范围判断', icon: '🔢', planned: true, fields: [F.col(), F.rule()] },
  { kind: 'null_check', category: 'operator', group: 'cleanse', label: '空值判断', icon: '❓', planned: true, fields: [F.col(), { key: 'defaultVal', label: '默认值', type: 'text' }] },
  { kind: 'dup_check', category: 'operator', group: 'cleanse', label: '重复判断', icon: '🔍', planned: true, fields: [F.col()] },
  { kind: 'url_check', category: 'operator', group: 'cleanse', label: 'URL检验', icon: '🔗', planned: true, fields: [F.col()] },
  { kind: 'id_check', category: 'operator', group: 'cleanse', label: '身份证检验', icon: '🪪', planned: true, fields: [F.col()] },
  { kind: 'regex_check', category: 'operator', group: 'cleanse', label: '正则检验', icon: '📐', planned: true, fields: [F.col(), { key: 'pattern', label: '正则', type: 'text' }] },
  { kind: 'data_validate', category: 'operator', group: 'cleanse', label: '数据校验', icon: '✅', planned: true, fields: [F.col(), F.rule()] },
  { kind: 'mask_partial', category: 'operator', group: 'mask', label: '部分遮盖', icon: '🫥', planned: true, fields: [F.col(), { key: 'keepHead', label: '保留前N位', type: 'number' }, { key: 'keepTail', label: '保留后N位', type: 'number' }] },
  { kind: 'mask_delete', category: 'operator', group: 'mask', label: '删除遮盖', icon: '🚫', planned: true, fields: [F.col()] },
  { kind: 'mask_random', category: 'operator', group: 'mask', label: '随机遮盖', icon: '🎲', planned: true, fields: [F.col()] },
  { kind: 'encrypt', category: 'operator', group: 'mask', label: '加解密', icon: '🔐', planned: true, fields: [F.col(), { key: 'encType', label: '加密类型', type: 'select', options: [{ label: 'AES', value: 'AES' }, { label: 'DES', value: 'DES' }] }] },
  { kind: 'rest_client', category: 'operator', group: 'query', label: 'REST Client', icon: '🌐', planned: true, fields: [{ key: 'url', label: 'URL', type: 'text' }, { key: 'method', label: '方法', type: 'select', options: [{ label: 'GET', value: 'GET' }, { label: 'POST', value: 'POST' }] }] },
  { kind: 'stream_lookup', category: 'operator', group: 'query', label: '流查询', icon: '🔎', planned: true, fields: [F.col(), F.onExpr()] },
  { kind: 'univariate', category: 'operator', group: 'stats', label: '单变量统计', icon: '📊', planned: true, fields: [F.col()] },
  { kind: 'sampling', category: 'operator', group: 'stats', label: '数据采样', icon: '🎯', planned: true, fields: [{ key: 'size', label: '取样数', type: 'number' }] },
  { kind: 'switch_case', category: 'operator', group: 'flow', label: 'Switch case', icon: '🔀', planned: true, fields: [F.col(), F.rule()] },
]

// 表输入节点（complex，数据源驱动）
const tableInput = (kind: string): NodeDef => ({
  kind, category: 'source', group: 'input', label: '表输入', icon: '📥', complex: true, planned: false,
  fields: [sourceTable('columns'), F.limit(), { key: 'allowLazyConversion', label: '允许简易转换', type: 'switch' }, { key: 'incremental', label: '增量获取', type: 'switch' }, { key: 'incrementalField', label: '增量字段', type: 'text', placeholder: '时间类型字段' }, sqlEditor()],
})
// 表输出节点（complex）
const tableOutput = (kind: string): NodeDef => ({
  kind, category: 'sink', group: 'output', label: '表输出', icon: '📤', complex: true, planned: false,
  fields: [sourceTable('table'), fieldMapping()],
})
// 插入/更新（planned, complex）
const INSERT_UPDATE: NodeDef = {
  kind: 'insert_update', category: 'sink', group: 'output', label: '插入/更新', icon: '💾', planned: true, complex: true,
  fields: [sourceTable('table'), keyMapping(), { key: 'updateFields', label: '更新字段', type: 'fieldsTable', mappingType: 'auto' }],
}

const CATALOG: Record<string, NodeDef[]> = {
  flink_dag: [
    tableInput('table'),
    { kind: 'kafka', category: 'source', group: 'input', label: 'Kafka消费', icon: '📨', planned: false, fields: [F.topic(), F.fields()] },
    { kind: 'csv_input', category: 'source', group: 'input', label: 'CSV文件输入', icon: '📄', planned: true, fields: [{ key: 'path', label: '文件路径', type: 'text' }, F.fields()] },
    { kind: 'excel_input', category: 'source', group: 'input', label: 'Excel文件输入', icon: '📊', planned: true, fields: [{ key: 'path', label: '文件路径', type: 'text' }, F.fields()] },
    { kind: 'json_input', category: 'source', group: 'input', label: 'Json输入', icon: '📋', planned: true, fields: [F.fields()] },
    { kind: 'generate_rows', category: 'source', group: 'input', label: '生成记录', icon: '⚙️', planned: true, fields: [F.fields()] },
    { kind: 'rest_input', category: 'source', group: 'input', label: 'REST Client输入', icon: '🌐', planned: true, fields: [{ key: 'url', label: 'URL', type: 'text' }] },
    { kind: 'xml_input', category: 'source', group: 'input', label: 'XML文件输入', icon: '📄', planned: true, fields: [{ key: 'path', label: '文件路径', type: 'text' }] },
    { kind: 'text_input', category: 'source', group: 'input', label: '文本文件输入', icon: '📝', planned: true, fields: [{ key: 'path', label: '文件路径', type: 'text' }] },
    ...CORE_OPS,
    { kind: 'udf', category: 'operator', group: 'transform', label: 'UDF函数', icon: '🔧', planned: false, fields: [{ key: 'udf', label: '函数名', type: 'text', placeholder: 'identity' }, F.col()] },
    ...PLANNED_OPS,
    tableOutput('table'),
    { kind: 'kafka', category: 'sink', group: 'output', label: 'Kafka输出', icon: '📨', planned: false, fields: [F.topic()] },
    { kind: 'excel_output', category: 'sink', group: 'output', label: 'Excel输出', icon: '📋', planned: true, fields: [{ key: 'path', label: '输出路径', type: 'text' }] },
    INSERT_UPDATE,
    { kind: 'json_output', category: 'sink', group: 'output', label: 'Json输出', icon: '📋', planned: true, fields: [F.fields()] },
  ],
  kettle_hop: [
    tableInput('table_input'),
    { kind: 'csv_input', category: 'source', group: 'input', label: 'CSV文件输入', icon: '📄', planned: true, fields: [{ key: 'path', label: '文件路径', type: 'text' }, F.fields()] },
    { kind: 'excel_input', category: 'source', group: 'input', label: 'Excel文件输入', icon: '📊', planned: true, fields: [{ key: 'path', label: '文件路径', type: 'text' }, F.fields()] },
    { kind: 'kafka_input', category: 'source', group: 'input', label: 'Kafka消费', icon: '📨', planned: true, fields: [F.topic(), F.fields()] },
    { kind: 'json_input', category: 'source', group: 'input', label: 'Json输入', icon: '📋', planned: true, fields: [F.fields()] },
    { kind: 'generate_rows', category: 'source', group: 'input', label: '生成记录', icon: '⚙️', planned: true, fields: [F.fields()] },
    { kind: 'rest_input', category: 'source', group: 'input', label: 'REST Client输入', icon: '🌐', planned: true, fields: [{ key: 'url', label: 'URL', type: 'text' }] },
    { kind: 'xml_input', category: 'source', group: 'input', label: 'XML文件输入', icon: '📄', planned: true, fields: [{ key: 'path', label: '文件路径', type: 'text' }] },
    { kind: 'text_input', category: 'source', group: 'input', label: '文本文件输入', icon: '📝', planned: true, fields: [{ key: 'path', label: '文件路径', type: 'text' }] },
    ...CORE_OPS,
    ...PLANNED_OPS,
    tableOutput('table_output'),
    { kind: 'kafka_output', category: 'sink', group: 'output', label: 'Kafka输出', icon: '📨', planned: true, fields: [F.topic()] },
    { kind: 'excel_output', category: 'sink', group: 'output', label: 'Excel输出', icon: '📋', planned: true, fields: [{ key: 'path', label: '输出路径', type: 'text' }] },
    INSERT_UPDATE,
    { kind: 'json_output', category: 'sink', group: 'output', label: 'Json输出', icon: '📋', planned: true, fields: [F.fields()] },
  ],
}

const defs = computed<NodeDef[]>(() => {
  const list = CATALOG[props.jobType || 'flink_dag'] || []
  // kettle_hop 走 Hop 引擎执行（KettleHopExecutor），所有算子都能跑，去掉"规划中"徽标
  return props.jobType === 'kettle_hop' ? list.map((d) => ({ ...d, planned: false })) : list
})
const groups = computed(() => {
  const seen: string[] = []
  for (const d of defs.value) if (!seen.includes(d.group)) seen.push(d.group)
  return seen
})
function nodesOf(g: string) { return defs.value.filter((d) => d.group === g) }
function defOf(category: string, kind: string) { return defs.value.find((d) => d.category === category && d.kind === kind) }
function fieldsOf(node: any): Field[] { return defOf(node.data.type, node.data.kind)?.fields || [] }
function iconFor(data: any) { return defOf(data.type, data.kind)?.icon || (data.type === 'source' ? '📥' : data.type === 'sink' ? '📤' : '⚙️') }
function groupOf(data: any) { return defOf(data.type, data.kind)?.group || '' }
function plannedOf(data: any) { return defOf(data.type, data.kind)?.planned }
function miniColor(n: any) { return CAT_COLOR[n.data?.type] || '#6b7d96' }
function defaultConfig(def: NodeDef): Record<string, any> {
  const c: Record<string, any> = {}
  for (const f of def.fields) {
    switch (f.type) {
      case 'number': c[f.key] = 0; break
      case 'fields': c[f.key] = []; break
      case 'switch': c[f.key] = false; break
      case 'select': c[f.key] = f.options?.[0]?.value || ''; break
      case 'fieldsTable': c[f.key] = []; break
      case 'sqlEditor': c[f.key] = { useSql: false, sql: '', fields: [] }; break
      case 'sourceTable': break  // 扁平字段，下面统一初始化
      default: c[f.key] = ''
    }
  }
  if (def.fields.some((f) => f.type === 'sourceTable')) {
    if (c.datasourceId === undefined) c.datasourceId = 0
    if (c.tableName === undefined) c.tableName = ''
    if (c.schemaName === undefined) c.schemaName = ''
    if (c.fields === undefined) c.fields = []
  }
  return c
}

// —— Vue Flow 命令式 API：用显式 id 关联 <VueFlow :id>，避免 drawer/懒挂载场景下 store 脱钩 ——
const flowId = 'dag-studio-flow'
const { addNodes, addEdges, removeNodes, setNodes, setEdges, screenToFlowCoordinate, toObject } = useVueFlow(flowId)
const selected = ref<any>(null)
const selectedDef = computed(() => selected.value ? defOf(selected.value.data.type, selected.value.data.kind) : null)
const empty = ref(true)
const datasources = ref<any[]>([])
let suppress = false
let lastEmitted = ''
let emitTimer: any = null

// 复杂节点配置弹窗
const configDlg = ref(false)
const dialogNode = ref<any>(null)
const dialogFields = computed<Field[]>(() => dialogNode.value ? fieldsOf(dialogNode.value) : [])
const dialogGroupLabel = computed(() => dialogNode.value ? (GROUP_LABEL[groupOf(dialogNode.value.data)] || '') : '')
const dialogPlanned = computed(() => dialogNode.value ? plannedOf(dialogNode.value.data) : false)

// 由 modelValue(dag_json) 载入到 Vue Flow 内部 store
watch(() => props.modelValue, (v) => {
  if (!v || v === lastEmitted) { if (!v) { setNodes([]); setEdges([]); empty.value = true } return }
  suppress = true
  try {
    const dag = JSON.parse(v)
    setNodes((dag.nodes || []).map((n: any) => ({
      id: n.id, type: 'step', position: n.position || { x: 0, y: 0 },
      data: { label: n.data?.label ?? n.kind ?? '节点', type: n.data?.type ?? n.type ?? 'operator', kind: n.data?.kind ?? '', config: n.data?.config ?? {} },
    })))
    setEdges((dag.edges || []).map((e: any) => ({ id: e.id, source: e.source, target: e.target })))
  } catch { /* ignore */ }
  nextTick(() => { suppress = false; lastEmitted = serialize() })
  empty.value = nodeCount() === 0
}, { immediate: true })

function onGraphChange() { if (suppress) return; empty.value = nodeCount() === 0; scheduleEmit() }
function onPaneClick() { selected.value = null }

// —— 拖拽：HTML5 DnD ——
let dropIdx = 0
function onDragStart(e: DragEvent, d: NodeDef) {
  if (e.dataTransfer) {
    const s = JSON.stringify(d)
    e.dataTransfer.setData('application/vueflow', s)
    e.dataTransfer.setData('text/plain', s)
    e.dataTransfer.effectAllowed = 'move'
  }
}
function onDragEnd() { /* 占位 */ }
function onDragOver(e: DragEvent) { e.preventDefault(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'move' }
function staggerPos() { const p = { x: 80 + (dropIdx % 4) * 150, y: 60 + Math.floor(dropIdx / 4) * 110 }; dropIdx++; return p }
function onDrop(e: DragEvent) {
  e.preventDefault()
  const raw = e.dataTransfer?.getData('application/vueflow') || e.dataTransfer?.getData('text/plain') || ''
  if (!raw) return
  let d: NodeDef
  try { d = JSON.parse(raw) } catch { return }
  let position = staggerPos()
  try {
    const p = screenToFlowCoordinate({ x: e.clientX, y: e.clientY })
    if (p && isFinite(p.x) && isFinite(p.y)) position = p
  } catch { /* 兜底网格位置 */ }
  const id = `n_${Date.now()}_${Math.floor(Math.random() * 1000)}`
  try {
    addNodes([{ id, type: 'step', position, data: { label: d.label, type: d.category, kind: d.kind, config: defaultConfig(d) } }])
    empty.value = false
    console.log('[DagEditor] onDrop', d.kind, 'id:', id, 'total:', toObject().nodes?.length)
  } catch (err) { console.error('[DagEditor] addNodes 失败', err) }
}

function onConnect(params: any) {
  if (params.source === params.target) return        // 防自环
  const exist = toObject().edges?.some((e: any) => e.source === params.source && e.target === params.target)
  if (exist) return                                   // 防重复边
  addEdges({ ...params, animated: true })
}
function onNodeClick({ node }: any) { selected.value = node }
function onNodeDoubleClick({ node }: any) {
  const def = defOf(node.data.type, node.data.kind)
  if (!def?.complex) return
  dialogNode.value = node
  configDlg.value = true
}
function openDialog(node: any) { dialogNode.value = node; configDlg.value = true }
function onDialogSave(config: any) {
  if (!dialogNode.value) return
  dialogNode.value.data.config = config
  configDlg.value = false
  scheduleEmit()
}
function setFields(key: string, val: string) {
  if (!selected.value) return
  selected.value.data.config[key] = String(val).split(',').map((s) => s.trim()).filter(Boolean)
  scheduleEmit()
}
function removeSelected() {
  if (!selected.value) return
  removeNodes([selected.value.id])
  selected.value = null
  scheduleEmit()
}

// —— 序列化 / 回灌 ——
function nodeCount(): number { try { return (toObject().nodes || []).length } catch { return 0 } }
function serialize(): string {
  let obj: any = {}
  try { obj = toObject() } catch { return '' }
  return JSON.stringify({
    nodes: (obj.nodes || []).map((n: any) => ({ id: n.id, type: n.data?.type || n.type, position: n.position, data: n.data })),
    edges: (obj.edges || []).map((e: any) => ({ id: e.id, source: e.source, target: e.target })),
  })
}
function scheduleEmit() {
  if (suppress) return
  if (emitTimer) clearTimeout(emitTimer)
  emitTimer = setTimeout(() => { lastEmitted = serialize(); emit('update:modelValue', lastEmitted) }, 60)
}

// —— DAG 校验（供 DagStudio 保存/运行前调用）——
function validate(): { ok: boolean; errors: string[]; warnings: string[] } {
  const errors: string[] = []
  const warnings: string[] = []
  let obj: any = {}
  try { obj = toObject() } catch { return { ok: false, errors: ['画布状态读取失败'], warnings } }
  const nodes: any[] = obj.nodes || []
  const edges: any[] = obj.edges || []
  if (nodes.length === 0) errors.push('画布无节点')
  if (!nodes.some((n) => n.data?.type === 'source')) errors.push('缺少输入(source)节点')
  if (!nodes.some((n) => n.data?.type === 'sink')) errors.push('缺少输出(sink)节点')
  if (hasCycle(nodes, edges)) errors.push('DAG 存在环路')
  for (const n of nodes) {
    const def = defOf(n.data?.type, n.data?.kind)
    if (!def) continue
    if (def.planned) warnings.push(`节点[${n.data?.label}]为规划中算子[${def.kind}]，运行将失败`)
    for (const f of def.fields) {
      if (f.type === 'sourceTable') {
        if (!n.data?.config?.tableName) errors.push(`节点[${n.data?.label}]未选择表`)
      } else if (f.type === 'switch' || f.type === 'sqlEditor' || f.type === 'fieldsTable') {
        // 非必填
      } else if (f.key === 'limit') {
        // 0=不限，合法
      } else {
        const v = n.data?.config?.[f.key]
        const empty = f.type === 'fields' ? (!Array.isArray(v) || v.length === 0) : (v === undefined || v === null || v === '')
        if (empty) errors.push(`节点[${n.data?.label}]缺少${f.label}`)
      }
    }
  }
  return { ok: errors.length === 0, errors, warnings }
}

function hasCycle(nodes: any[], edges: any[]): boolean {
  const adj: Record<string, string[]> = {}
  for (const n of nodes) adj[n.id] = []
  for (const e of edges) { if (adj[e.source]) adj[e.source].push(e.target) }
  const visited: Record<string, number> = {}
  function dfs(id: string): boolean {
    if (visited[id] === 1) return true
    if (visited[id] === 2) return false
    visited[id] = 1
    for (const nb of adj[id] || []) if (dfs(nb)) return true
    visited[id] = 2
    return false
  }
  for (const n of nodes) if (dfs(n.id)) return true
  return false
}

onMounted(async () => { try { datasources.value = await api.daSources() } catch { /* 静默 */ } })

defineExpose({ validate, serialize })
</script>

<style scoped>
.dag-wrap { display: flex; gap: 8px; height: 100%; min-height: 420px; border: 1px solid var(--tech-panel-border, #2a3a52); border-radius: 8px; overflow: hidden; background: #0a111c; box-shadow: inset 0 0 0 1px rgba(255,255,255,0.02); }

/* 左面板 */
.dag-panel { width: 168px; flex-shrink: 0; background: linear-gradient(180deg, rgba(20,30,46,0.95), rgba(14,22,34,0.95)); padding: 10px; overflow-y: auto; border-right: 1px solid rgba(255,255,255,0.05); }
.dag-panel-title { display: flex; align-items: baseline; justify-content: space-between; font-size: 12px; color: #c8d4e3; font-weight: 700; margin-bottom: 10px; letter-spacing: .5px; }
.dag-panel-sub { font-size: 10px; color: #5d7088; font-weight: 400; }
.dag-cat { margin-bottom: 10px; }
.dag-cat-title { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #8295ad; margin: 2px 0 6px; font-weight: 600; }
.cat-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.dag-node-tpl { display: flex; align-items: center; gap: 7px; padding: 7px 9px; margin-bottom: 6px; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.07); border-radius: 6px; cursor: grab; font-size: 12px; color: #cfdcec; transition: transform .12s, border-color .12s, background .12s; }
.dag-node-tpl:hover { transform: translateY(-1px); border-color: rgba(64,158,255,0.6); background: rgba(64,158,255,0.08); }
.dag-node-tpl:active { cursor: grabbing; }
.tpl-icon { width: 22px; height: 22px; border-radius: 5px; display: inline-flex; align-items: center; justify-content: center; font-size: 13px; flex-shrink: 0; }
.tpl-label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tpl-badge { font-size: 9px; color: #e6a23c; border: 1px solid #e6a23c44; padding: 0 4px; border-radius: 3px; margin-left: auto; line-height: 1.4; flex-shrink: 0; }
.dag-tip { color: #5d7088; font-size: 11px; line-height: 1.6; margin-top: 10px; padding-top: 8px; border-top: 1px dashed rgba(255,255,255,0.07); }

/* 画布 */
.dag-canvas { flex: 1; position: relative; background: radial-gradient(ellipse at center, #131e2e 0%, #0a111c 80%); }
.dag-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: #3d4e66; font-size: 13px; pointer-events: none; letter-spacing: 1px; }

/* 右属性面板 */
.dag-prop { width: 270px; flex-shrink: 0; background: linear-gradient(180deg, rgba(20,30,46,0.95), rgba(14,22,34,0.95)); padding: 10px; overflow-y: auto; border-left: 1px solid rgba(255,255,255,0.05); }
.dag-prop-empty { color: #5d7088; font-size: 12px; padding: 20px 4px; text-align: center; }
.prop-row { margin-bottom: 11px; }
.prop-row label { display: block; font-size: 11px; color: #8295ad; margin-bottom: 4px; }
.prop-cat { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #cfdcec; }
.prop-complex { background: rgba(64,158,255,0.06); border: 1px dashed rgba(64,158,255,0.4); border-radius: 6px; padding: 12px; margin-bottom: 12px; text-align: center; }
.prop-complex-hint { color: #5d7088; font-size: 11px; margin-top: 6px; }
.prop-complex-info { display: flex; justify-content: center; gap: 10px; margin-top: 8px; font-size: 11px; color: #8295ad; }
.mono :deep(textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; }
.num { width: 100%; }

/* 自定义节点 */
.step-node { min-width: 124px; background: linear-gradient(180deg, #23344f 0%, #1a2738 100%); border: 1px solid #2f4263; border-left: 4px solid #6b7d96; border-radius: 7px; box-shadow: 0 4px 14px rgba(0,0,0,0.5); transition: box-shadow .15s, transform .12s; font-size: 12px; color: #e3edf9; }
.step-node:hover { transform: translateY(-1px); }
.step-node.cat-source { border-left-color: #67c23a; }
.step-node.cat-operator { border-left-color: #409eff; }
.step-node.cat-sink { border-left-color: #e6a23c; }
.step-node.step-sel { box-shadow: 0 0 0 2px #409eff, 0 8px 22px rgba(64,158,255,0.45); }
.step-head { display: flex; align-items: center; gap: 7px; padding: 8px 10px 4px; }
.step-icon { font-size: 15px; line-height: 1; }
.step-label { font-weight: 600; color: #eef4fb; white-space: nowrap; }
.step-planned { font-size: 9px; color: #e6a23c; border: 1px solid #e6a23c44; padding: 0 4px; border-radius: 3px; margin-left: auto; }
.step-kind { display: flex; align-items: center; gap: 5px; font-size: 10px; color: #7d92ad; padding: 3px 10px 8px; }
.kind-tag { background: rgba(255,255,255,0.08); padding: 1px 6px; border-radius: 8px; }

/* 连接点 / 连线 */
.dag-canvas :deep(.vue-flow__handle) { width: 9px; height: 9px; background: #5b7050; border: 2px solid #0a111c; }
.dag-canvas :deep(.vue-flow__handle.source) { background: #409eff; }
.dag-canvas :deep(.vue-flow__handle.target) { background: #67c23a; }
.dag-canvas :deep(.vue-flow__edge-path) { stroke: #3d5474; stroke-width: 2; }
.dag-canvas :deep(.vue-flow__edge.selected .vue-flow__edge-path),
.dag-canvas :deep(.vue-flow__edge:hover .vue-flow__edge-path) { stroke: #409eff; }
.dag-canvas :deep(.vue-flow__edge.animated .vue-flow__edge-path) { stroke-dasharray: 6 4; animation: dash 1s linear infinite; }
@keyframes dash { to { stroke-dashoffset: -10; } }
.dag-canvas :deep(.vue-flow__controls) { box-shadow: 0 2px 8px rgba(0,0,0,0.4); border-radius: 6px; overflow: hidden; }
.dag-canvas :deep(.vue-flow__minimap) { border-radius: 6px; overflow: hidden; border: 1px solid rgba(255,255,255,0.08); }
</style>
