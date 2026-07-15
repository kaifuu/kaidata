<template>
  <div class="dag-wrap">
    <!-- 左：节点面板（搜索 + 可折叠分组，拖到画布） -->
    <div class="dag-panel">
      <div class="dag-panel-title">
        <span>节点面板</span>
        <span class="dag-panel-sub">拖到画布</span>
      </div>
      <el-input v-model="search" size="small" placeholder="搜索节点…" clearable class="dag-search">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <div v-for="g in visibleGroups" :key="g" class="dag-cat">
        <div class="dag-cat-title" @click="toggleGroup(g)">
          <i class="cat-dot" :style="{ background: GROUP_VAR[g] }"></i>
          <span class="dag-cat-name">{{ GROUP_LABEL[g] }}</span>
          <el-icon class="chevron" :class="{ open: !collapsed.has(g) }"><ArrowRight /></el-icon>
        </div>
        <div v-show="!collapsed.has(g)" class="dag-cat-body">
          <div v-for="d in filteredNodesOf(g)" :key="d.kind" class="dag-node-tpl" draggable="true"
               @dragstart="onDragStart($event, d)" @dragend="onDragEnd">
            <span class="tpl-icon" :style="{ '--accent': GROUP_VAR[g] }"><StepIcon :icon="d.icon" :group="d.group" :kind="d.kind" :size="14" /></span>
            <span class="tpl-label">{{ d.label }}</span>
          </div>
        </div>
      </div>
      <div v-if="!visibleGroups.length" class="dag-empty-tpl">无匹配节点</div>
      <div class="dag-tip">① 拖节点到画布　② 拖节点上下锚点连线　③ 双击节点编辑属性</div>
    </div>

    <!-- 中：画布 -->
    <div class="dag-canvas" @drop="onDrop" @dragover="onDragOver" @dragenter.prevent>
      <VueFlow :id="flowId" :fit-view-on-init="true" :delete-key-code="['Backspace','Delete']" :min-zoom="0.3" :max-zoom="2"
               :default-edge-options="defaultEdgeOpts"
               @connect="onConnect" @node-click="onNodeClick" @pane-click="onPaneClick"
               @node-double-click="onNodeDoubleClick" @node-drag-stop="scheduleEmit" @nodes-change="onGraphChange" @edges-change="onGraphChange">
        <Background :gap="18" :size="1.2" :pattern-color="gridColor" />
        <Controls position="bottom-right" />
        <MiniMap :node-color="miniColor" :mask-color="miniMask" />
        <template #node-step="{ data, selected: sel }">
          <div class="step-node" :class="['cat-' + data.type, { sel, planned: plannedOf(data) }]">
            <Handle type="target" :position="Position.Top" class="hop-in" />
            <div class="step-header">
              <StepIcon :icon="iconFor(data)" :kind="data.kind" :group="groupOf(data)" :size="16" class="step-h-icon" />
              <span class="step-title">{{ data.label }}</span>
              <span v-if="plannedOf(data)" class="step-flag">引擎不支持</span>
            </div>
            <div class="step-body">
              <span class="step-cat-tag">{{ GROUP_LABEL[groupOf(data)] || data.type }}</span>
              <span class="step-summary">{{ summaryOf(data) }}</span>
            </div>
            <Handle type="source" :position="Position.Bottom" class="hop-out" />
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
          <div class="prop-cat"><i class="cat-dot" :style="{ background: GROUP_VAR[groupOf(selected.data)] || 'var(--tech-text-muted)' }"></i>{{ GROUP_LABEL[groupOf(selected.data)] || selected.data.type }} · {{ selected.data.kind }}</div>
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
import { VueFlow, useVueFlow, Handle, Position, MarkerType } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { Search, ArrowRight } from '@element-plus/icons-vue'
import { api } from '@/api'
import { theme } from '@/theme'
import NodeConfigDialog from './NodeConfigDialog.vue'
import StepIcon from './StepIcon.vue'
import { resolveIcon } from './dag-icons'
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
// 引擎支持矩阵：缺省 = 两端都支持。
//   KETTLE_ONLY = 纯 FlinkSQL 表达不了（JS/Java 代码、加解密、随机、REST、文件 IO、维表）
//   FLINK_ONLY  = KettleXmlBuilder 未实现该 kind（udf）
type EngineSupport = { flink: boolean; kettle: boolean }
const KETTLE_ONLY: EngineSupport = { flink: false, kettle: true }
const FLINK_ONLY: EngineSupport = { flink: true, kettle: false }
type NodeDef = {
  kind: string; category: 'source' | 'operator' | 'sink'; group: string
  label: string; icon: string; fields: Field[]
  complex?: boolean; support?: EngineSupport   // 缺省 = 两端都支持
}

const props = defineProps<{ modelValue?: string; jobType?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

// 8 类面板分组
const GROUP_LABEL: Record<string, string> = {
  input: '输入', transform: '数据转换', cleanse: '数据清洗', mask: '脱敏处理',
  query: '查询', stats: '统计', flow: '流程', output: '输出',
}
const GROUP_VAR: Record<string, string> = {
  input: 'var(--tech-success)', transform: 'var(--tech-primary)', cleanse: 'var(--tech-warn)', mask: 'var(--tech-danger)',
  query: 'var(--tech-text-muted)', stats: 'var(--tech-accent)', flow: 'var(--tech-primary-2)', output: 'var(--tech-warn)',
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
// 已实现 FlinkSQL 翻译的转换算子（flink_dag 可用，不再标"规划中"）
const IMPL_OPS: NodeDef[] = [
  { kind: 'join', category: 'operator', group: 'transform', label: '记录集连接', icon: '🔗', complex: true, fields: [F.joinType(), F.onExpr()] },
  { kind: 'string_replace', category: 'operator', group: 'transform', label: '字符串替换', icon: '🔁', fields: [F.col(), { key: 'from', label: '查找', type: 'text' }, { key: 'to', label: '替换为', type: 'text' }] },
  { kind: 'string_ops', category: 'operator', group: 'transform', label: '字符串操作', icon: '🔤', fields: [F.col(), F.rule()] },
  { kind: 'split_field', category: 'operator', group: 'transform', label: '拆分字段', icon: '✂️', fields: [F.col(), { key: 'delimiter', label: '分隔符', type: 'text', placeholder: ',' }] },
  { kind: 'string_to_date', category: 'operator', group: 'transform', label: '字符串转日期', icon: '📅', fields: [F.col(), { key: 'format', label: '格式', type: 'text', placeholder: 'yyyy-MM-dd' }] },
  { kind: 'exec_sql', category: 'operator', group: 'transform', label: '执行SQL脚本', icon: '🗄️', fields: [{ key: 'expression', label: 'SQL', type: 'textarea', rows: 4 }] },
  { kind: 'num_range', category: 'operator', group: 'cleanse', label: '数值范围判断', icon: '🔢', fields: [F.col(), F.rule()] },
  { kind: 'null_check', category: 'operator', group: 'cleanse', label: '空值判断', icon: '❓', fields: [F.col(), { key: 'defaultVal', label: '默认值', type: 'text' }] },
  { kind: 'dup_check', category: 'operator', group: 'cleanse', label: '重复判断', icon: '🔍', fields: [F.col()] },
  { kind: 'url_check', category: 'operator', group: 'cleanse', label: 'URL检验', icon: '🔗', fields: [F.col()] },
  { kind: 'id_check', category: 'operator', group: 'cleanse', label: '身份证检验', icon: '🪪', fields: [F.col()] },
  { kind: 'regex_check', category: 'operator', group: 'cleanse', label: '正则检验', icon: '📐', fields: [F.col(), { key: 'pattern', label: '正则', type: 'text' }] },
  { kind: 'data_validate', category: 'operator', group: 'cleanse', label: '数据校验', icon: '✅', fields: [F.col(), F.rule()] },
  { kind: 'mask_partial', category: 'operator', group: 'mask', label: '部分遮盖', icon: '🫥', fields: [F.col(), { key: 'keepHead', label: '保留前N位', type: 'number' }, { key: 'keepTail', label: '保留后N位', type: 'number' }] },
  { kind: 'mask_delete', category: 'operator', group: 'mask', label: '删除遮盖', icon: '🚫', fields: [F.col()] },
  { kind: 'univariate', category: 'operator', group: 'stats', label: '单变量统计', icon: '📊', fields: [F.col()] },
  { kind: 'sampling', category: 'operator', group: 'stats', label: '数据采样', icon: '🎯', fields: [{ key: 'size', label: '取样数', type: 'number' }] },
  { kind: 'switch_case', category: 'operator', group: 'flow', label: 'Switch case', icon: '🔀', fields: [F.col(), F.rule()] },
]
// 仅 Kettle/Hop 支持的算子（纯 FlinkSQL 表达不了：任意代码执行 / HTTP / 加解密 / 随机 / 维表）—— support: KETTLE_ONLY
const KETTLE_ONLY_OPS: NodeDef[] = [
  { kind: 'js_code', category: 'operator', group: 'transform', label: 'JavaScript代码', icon: '📜', support: KETTLE_ONLY, fields: [{ key: 'expression', label: 'JS脚本', type: 'textarea', rows: 4 }] },
  { kind: 'java_code', category: 'operator', group: 'transform', label: 'Java代码', icon: '☕', support: KETTLE_ONLY, fields: [{ key: 'expression', label: 'Java代码', type: 'textarea', rows: 4 }] },
  { kind: 'mask_random', category: 'operator', group: 'mask', label: '随机遮盖', icon: '🎲', support: KETTLE_ONLY, fields: [F.col()] },
  { kind: 'encrypt', category: 'operator', group: 'mask', label: '加解密', icon: '🔐', support: KETTLE_ONLY, fields: [F.col(), { key: 'encType', label: '加密类型', type: 'select', options: [{ label: 'AES', value: 'AES' }, { label: 'DES', value: 'DES' }] }] },
  { kind: 'rest_client', category: 'operator', group: 'query', label: 'REST Client', icon: '🌐', support: KETTLE_ONLY, fields: [{ key: 'url', label: 'URL', type: 'text' }, { key: 'method', label: '方法', type: 'select', options: [{ label: 'GET', value: 'GET' }, { label: 'POST', value: 'POST' }] }] },
  { kind: 'stream_lookup', category: 'operator', group: 'query', label: '流查询', icon: '🔎', support: KETTLE_ONLY, fields: [F.col(), F.onExpr()] },
]

// 表输入节点（complex，数据源驱动；两端均支持）
const tableInput = (kind: string): NodeDef => ({
  kind, category: 'source', group: 'input', label: '表输入', icon: '📥', complex: true,
  fields: [sourceTable('columns'), F.limit(), { key: 'allowLazyConversion', label: '允许简易转换', type: 'switch' }, { key: 'incremental', label: '增量获取', type: 'switch' }, { key: 'incrementalField', label: '增量字段', type: 'text', placeholder: '时间类型字段' }, sqlEditor()],
})
// 表输出节点（complex；两端均支持）
const tableOutput = (kind: string): NodeDef => ({
  kind, category: 'sink', group: 'output', label: '表输出', icon: '📤', complex: true,
  fields: [sourceTable('table'), fieldMapping()],
})
// 插入/更新（仅 Kettle；Flink 无 upsert 语义翻译）
const INSERT_UPDATE: NodeDef = {
  kind: 'insert_update', category: 'sink', group: 'output', label: '插入/更新', icon: '💾', support: KETTLE_ONLY, complex: true,
  fields: [sourceTable('table'), keyMapping(), { key: 'updateFields', label: '更新字段', type: 'fieldsTable', mappingType: 'auto' }],
}

// 全量算子目录（flink_dag / kettle_hop 共用同一面板，按 support 矩阵 + 当前引擎过滤）。
// 注意：DAG 引擎绑定、不可跨引擎移植——同名 kind 在两端语义可能不同（如 join 排序要求、aggregate 实现）；
//       共用仅限编排 UX。kind 统一为 table / kafka_input / kafka_output（两端后端均兼容）。
const SHARED_OPS: NodeDef[] = [
  // —— 输入 ——
  tableInput('table'),
  { kind: 'kafka_input', category: 'source', group: 'input', label: 'Kafka消费', icon: '📨', fields: [F.topic(), F.fields()] },
  { kind: 'csv_input', category: 'source', group: 'input', label: 'CSV文件输入', icon: '📄', support: KETTLE_ONLY, fields: [{ key: 'path', label: '文件路径', type: 'text' }, F.fields()] },
  { kind: 'excel_input', category: 'source', group: 'input', label: 'Excel文件输入', icon: '📊', support: KETTLE_ONLY, fields: [{ key: 'path', label: '文件路径', type: 'text' }, F.fields()] },
  { kind: 'json_input', category: 'source', group: 'input', label: 'Json输入', icon: '📋', support: KETTLE_ONLY, fields: [F.fields()] },
  { kind: 'generate_rows', category: 'source', group: 'input', label: '生成记录', icon: '⚙️', support: KETTLE_ONLY, fields: [F.fields()] },
  { kind: 'rest_input', category: 'source', group: 'input', label: 'REST Client输入', icon: '🌐', support: KETTLE_ONLY, fields: [{ key: 'url', label: 'URL', type: 'text' }] },
  { kind: 'xml_input', category: 'source', group: 'input', label: 'XML文件输入', icon: '📄', support: KETTLE_ONLY, fields: [{ key: 'path', label: '文件路径', type: 'text' }] },
  { kind: 'text_input', category: 'source', group: 'input', label: '文本文件输入', icon: '📝', support: KETTLE_ONLY, fields: [{ key: 'path', label: '文件路径', type: 'text' }] },
  // —— 转换 / 清洗 / 脱敏 / 查询 / 统计 / 流程 ——
  ...CORE_OPS,
  ...IMPL_OPS,
  { kind: 'udf', category: 'operator', group: 'transform', label: 'UDF函数', icon: '🔧', support: FLINK_ONLY, fields: [{ key: 'udf', label: '函数名', type: 'text', placeholder: 'identity' }, F.col()] },
  ...KETTLE_ONLY_OPS,
  // —— 输出 ——
  tableOutput('table'),
  { kind: 'kafka_output', category: 'sink', group: 'output', label: 'Kafka输出', icon: '📨', fields: [F.topic()] },
  { kind: 'excel_output', category: 'sink', group: 'output', label: 'Excel输出', icon: '📋', support: KETTLE_ONLY, fields: [{ key: 'path', label: '输出路径', type: 'text' }] },
  INSERT_UPDATE,
  { kind: 'json_output', category: 'sink', group: 'output', label: 'Json输出', icon: '📋', support: KETTLE_ONLY, fields: [F.fields()] },
]

// 当前执行引擎：'flink' | 'kettle'
const engine = computed<'flink' | 'kettle'>(() => (props.jobType || 'flink_dag') === 'flink_dag' ? 'flink' : 'kettle')
// 算子是否支持指定引擎（缺省 support = 两端都支持）
function supportedOn(def: NodeDef, eng: 'flink' | 'kettle' = engine.value): boolean { return def.support?.[eng] ?? true }
// 全量目录（含两端专属算子），供画布节点 defOf 解析；面板渲染走 paletteDefs 按引擎过滤
const defs = computed<NodeDef[]>(() => SHARED_OPS)
// 面板可见算子：仅当前引擎支持的（不支持的算子不出现在左面板，也无法新增）
const paletteDefs = computed<NodeDef[]>(() => defs.value.filter((d) => supportedOn(d)))
// 分组也按引擎过滤：某组在当前引擎下无任何可用算子时整组隐藏（如 flink 下的"查询"组）
const groups = computed(() => {
  const seen: string[] = []
  for (const d of paletteDefs.value) if (!seen.includes(d.group)) seen.push(d.group)
  return seen
})
function nodesOf(g: string) { return paletteDefs.value.filter((d) => d.group === g) }
function defOf(category: string, kind: string) { return defs.value.find((d) => d.category === category && d.kind === kind) }
function fieldsOf(node: any): Field[] { return defOf(node.data.type, node.data.kind)?.fields || [] }
function iconFor(data: any): string { const def = defOf(data.type, data.kind); return resolveIcon(def?.icon, def?.group, data.kind) }
function groupOf(data: any) { return defOf(data.type, data.kind)?.group || '' }
// 当前引擎是否不支持该画布节点（仅旧数据/跨引擎数据会出现：面板已按引擎过滤，新增不出来）
function plannedOf(data: any) { const def = defOf(data.type, data.kind); return def ? !supportedOn(def) : false }
function miniColor(n: any) { return CAT_COLOR[n.data?.type] || '#6b7d96' }

// hop 连线：smoothstep 折线 + 末端箭头（经 vue-flow parseEdge 自动合并到重载边，serialize 不变）
const defaultEdgeOpts = { type: 'smoothstep', animated: true, markerEnd: MarkerType.ArrowClosed }
const gridColor = computed(() => (theme.isDark.value ? 'rgba(0,224,255,0.07)' : 'rgba(21,87,239,0.08)'))
const miniMask = computed(() => (theme.isDark.value ? 'rgba(6,12,28,0.7)' : 'rgba(21,87,239,0.07)'))

// hop 类型着色：按 source 节点 category 运行时推导（class 字段 serialize 时自动剔除，不污染 dag_json）
function hopClass(e: any): string {
  let src: string | undefined
  try { src = (toObject().nodes || []).find((n: any) => n.id === e.source)?.data?.type } catch { /* */ }
  return src === 'sink' ? 'hop-error' : 'hop-data'
}

// 节点配置摘要（Kettle step body 元信息）
function summaryOf(data: any): string {
  const c = data?.config
  if (c?.tableName) return c.tableName
  if (Array.isArray(c?.fields) && c.fields.length) return `${c.fields.length} 字段`
  if (c?.topic) return c.topic
  return data?.kind || ''
}

// 左面板：搜索 + 折叠（记忆到 localStorage，按 jobType 隔离）
const search = ref('')
const COLLAPSE_KEY = 'dag_collapsed_' + (props.jobType || 'flink_dag')
const collapsed = ref<Set<string>>(new Set<string>(((JSON.parse(localStorage.getItem(COLLAPSE_KEY) || '[]')) as string[]) || []))
function toggleGroup(g: string) {
  const s = new Set(collapsed.value)
  if (s.has(g)) s.delete(g); else s.add(g)
  collapsed.value = s
  localStorage.setItem(COLLAPSE_KEY, JSON.stringify([...s]))
}
function matchSearch(d: NodeDef): boolean {
  const q = search.value.trim().toLowerCase()
  if (!q) return true
  return d.label.toLowerCase().includes(q) || d.kind.toLowerCase().includes(q)
}
const visibleGroups = computed(() => groups.value.filter((g) => nodesOf(g).some((d) => matchSearch(d))))
function filteredNodesOf(g: string) { return nodesOf(g).filter((d) => matchSearch(d)) }
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
    setEdges((dag.edges || []).map((e: any) => ({ id: e.id, source: e.source, target: e.target, class: hopClass(e) })))
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
  addEdges({ ...params, ...defaultEdgeOpts, class: hopClass(params) })
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
    if (!supportedOn(def)) errors.push(`节点[${n.data?.label}]算子[${def.kind}]不支持当前引擎（${engine.value === 'flink' ? 'FlinkSQL' : 'Kettle/Hop'}），请删除或更换`)
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
.dag-wrap { display: flex; gap: 8px; height: 100%; min-height: 420px; border: 1px solid var(--tech-panel-border); border-radius: 8px; overflow: hidden; background: var(--tech-bg-2); box-shadow: var(--tech-shadow); }

/* 左面板 */
.dag-panel { width: 176px; flex-shrink: 0; background: var(--tech-panel); padding: 10px; overflow-y: auto; border-right: 1px solid var(--tech-panel-border); }
.dag-panel-title { display: flex; align-items: baseline; justify-content: space-between; font-size: 12px; color: var(--tech-text); font-weight: 700; margin-bottom: 8px; letter-spacing: .5px; }
.dag-panel-sub { font-size: 10px; color: var(--tech-text-muted); font-weight: 400; }
.dag-search { margin-bottom: 8px; }
.dag-cat { margin-bottom: 6px; }
.dag-cat-title { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--tech-text-muted); margin: 2px 0 4px; font-weight: 600; cursor: pointer; user-select: none; padding: 2px 0; border-radius: 4px; }
.dag-cat-title:hover { color: var(--tech-text); }
.dag-cat-name { flex: 1; }
.cat-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; flex-shrink: 0; }
.chevron { font-size: 10px; color: var(--tech-text-muted); transition: transform .15s; }
.chevron.open { transform: rotate(90deg); }
.dag-cat-body { padding-left: 2px; }
.dag-node-tpl { display: flex; align-items: center; gap: 7px; padding: 6px 8px; margin-bottom: 4px; background: var(--el-fill-color-light); border: 1px solid var(--tech-panel-border); border-radius: 6px; cursor: grab; font-size: 12px; color: var(--tech-text); transition: transform .12s, border-color .12s, background .12s; }
.dag-node-tpl:hover { transform: translateY(-1px); border-color: color-mix(in srgb, var(--tech-primary) 45%, transparent); background: color-mix(in srgb, var(--tech-primary) 8%, var(--el-fill-color-light)); }
.dag-node-tpl:active { cursor: grabbing; }
.tpl-icon { width: 22px; height: 22px; border-radius: 5px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; color: var(--accent, var(--tech-text-muted)); background: color-mix(in srgb, var(--accent, var(--tech-text-muted)) 16%, transparent); }
.tpl-label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tpl-badge { font-size: 9px; color: var(--tech-warn); border: 1px solid color-mix(in srgb, var(--tech-warn) 40%, transparent); padding: 0 4px; border-radius: 3px; margin-left: auto; line-height: 1.4; flex-shrink: 0; }
.dag-empty-tpl { color: var(--tech-text-muted); font-size: 11px; text-align: center; padding: 16px 4px; }
.dag-tip { color: var(--tech-text-muted); font-size: 11px; line-height: 1.6; margin-top: 10px; padding-top: 8px; border-top: 1px dashed var(--tech-panel-border); }

/* 画布 */
.dag-canvas { flex: 1; position: relative; background: var(--tech-bg); }
.dag-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--tech-text-muted); font-size: 13px; pointer-events: none; letter-spacing: 1px; opacity: .5; }

/* 右属性面板 */
.dag-prop { width: 272px; flex-shrink: 0; background: var(--tech-panel); padding: 10px; overflow-y: auto; border-left: 1px solid var(--tech-panel-border); }
.dag-prop-empty { color: var(--tech-text-muted); font-size: 12px; padding: 20px 4px; text-align: center; }
.prop-row { margin-bottom: 11px; }
.prop-row label { display: block; font-size: 11px; color: var(--tech-text-muted); margin-bottom: 4px; }
.prop-cat { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--tech-text); }
.prop-complex { background: color-mix(in srgb, var(--tech-primary) 7%, transparent); border: 1px dashed color-mix(in srgb, var(--tech-primary) 40%, transparent); border-radius: 6px; padding: 12px; margin-bottom: 12px; text-align: center; }
.prop-complex-hint { color: var(--tech-text-muted); font-size: 11px; margin-top: 6px; }
.prop-complex-info { display: flex; justify-content: center; gap: 10px; margin-top: 8px; font-size: 11px; color: var(--tech-text-muted); }
.mono :deep(textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; }
.num { width: 100%; }

/* 自定义节点（Kettle step 卡片：顶部彩色 header + body + 显式 hop 锚点） */
.step-node { --step-accent: var(--tech-text-muted); min-width: 152px; border-radius: 8px; overflow: hidden; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); box-shadow: var(--tech-shadow); color: var(--tech-text); font-size: 12px; transition: box-shadow .15s, transform .12s, border-color .15s; }
.step-node.cat-source { --step-accent: var(--tech-success); }
.step-node.cat-operator { --step-accent: var(--tech-primary); }
.step-node.cat-sink { --step-accent: var(--tech-warn); }
.step-node:hover { transform: translateY(-1px); border-color: var(--step-accent); }
.step-node.sel { box-shadow: 0 0 0 2px var(--step-accent), var(--tech-shadow); }
.step-node.planned { opacity: .92; }
.step-header { display: flex; align-items: center; gap: 6px; padding: 6px 8px; background: color-mix(in srgb, var(--step-accent) 14%, var(--tech-panel)); border-left: 3px solid var(--step-accent); }
.step-h-icon { color: var(--step-accent); flex-shrink: 0; }
.step-title { font-weight: 600; color: var(--tech-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; }
.step-flag { font-size: 9px; color: var(--tech-warn); border: 1px solid color-mix(in srgb, var(--tech-warn) 40%, transparent); padding: 0 4px; border-radius: 3px; flex-shrink: 0; }
.step-body { display: flex; align-items: center; gap: 6px; padding: 4px 8px 6px; }
.step-cat-tag { font-size: 10px; color: var(--step-accent); background: color-mix(in srgb, var(--step-accent) 14%, transparent); padding: 1px 6px; border-radius: 8px; flex-shrink: 0; }
.step-summary { font-size: 10px; color: var(--tech-text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 连接点 / hop 连线 */
.dag-canvas :deep(.vue-flow__handle) { width: 10px; height: 10px; border: 2px solid var(--tech-panel); }
.dag-canvas :deep(.vue-flow__handle.hop-in) { background: var(--tech-success); }
.dag-canvas :deep(.vue-flow__handle.hop-out) { background: var(--tech-primary); }
.dag-canvas :deep(.vue-flow__edge-path) { stroke: var(--tech-primary); stroke-width: 2; }
.dag-canvas :deep(.vue-flow__edge.hop-error .vue-flow__edge-path) { stroke: var(--tech-danger); }
.dag-canvas :deep(.vue-flow__edge:hover .vue-flow__edge-path),
.dag-canvas :deep(.vue-flow__edge.selected .vue-flow__edge-path) { stroke: var(--tech-accent); stroke-width: 2.5; }
.dag-canvas :deep(.vue-flow__edge.animated .vue-flow__edge-path) { stroke-dasharray: 6 4; animation: dash 1s linear infinite; }
@keyframes dash { to { stroke-dashoffset: -10; } }

/* vue-flow 内置组件：双主题适配（浅色下不黑成一团） */
.dag-canvas :deep(.vue-flow__controls) { box-shadow: var(--tech-shadow); border-radius: 6px; overflow: hidden; border: 1px solid var(--tech-panel-border); }
.dag-canvas :deep(.vue-flow__controls-button) { background: var(--tech-panel); border-bottom: 1px solid var(--tech-panel-border); color: var(--tech-text-muted); fill: var(--tech-text-muted); }
.dag-canvas :deep(.vue-flow__controls-button:hover) { background: var(--el-fill-color); }
.dag-canvas :deep(.vue-flow__controls-button svg) { fill: var(--tech-text-muted); }
.dag-canvas :deep(.vue-flow__minimap) { border-radius: 6px; overflow: hidden; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); }
</style>
