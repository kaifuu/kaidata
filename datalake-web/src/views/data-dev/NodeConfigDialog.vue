<template>
  <el-dialog :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)"
             :title="(node?.data?.label || '节点') + ' · 详细配置'" width="680px" destroy-on-close>
    <div class="cfg-form">
      <div class="cfg-row cfg-info">
        <span class="info-tag">{{ groupLabel }}</span>
        <span class="info-kind">{{ node?.data?.kind }}</span>
        <span v-if="planned" class="planned-tag">规划中（运行将失败）</span>
      </div>
      <div v-for="f in fields" :key="f.key" class="cfg-row">
        <label>{{ f.label }}</label>
        <el-input v-if="f.type === 'text'" v-model="cfg[f.key]" size="small" :placeholder="f.placeholder" />
        <el-input v-else-if="f.type === 'textarea'" v-model="cfg[f.key]" type="textarea" :rows="f.rows || 3" class="mono" :placeholder="f.placeholder" />
        <el-input-number v-else-if="f.type === 'number'" v-model="cfg[f.key]" size="small" :min="0" controls-position="right" />
        <el-input v-else-if="f.type === 'fields'" :model-value="(cfg[f.key] || []).join(', ')"
                  @update:model-value="setFields(f.key, $event)" size="small" :placeholder="f.placeholder || '逗号分隔'" />
        <el-select v-else-if="f.type === 'select'" v-model="cfg[f.key]" size="small" :placeholder="f.placeholder" filterable style="width:100%">
          <el-option v-for="o in f.options || []" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-switch v-else-if="f.type === 'switch'" v-model="cfg[f.key]" />
        <SourceTablePicker v-else-if="f.type === 'sourceTable'"
                           :model-value="cfg" :datasources="datasources" :select-mode="f.selectMode || 'columns'"
                           @update:model-value="mergeCfg" />
        <FieldMappingTable v-else-if="f.type === 'fieldsTable'"
                           :model-value="cfg[f.key] || []" :mapping-type="f.mappingType || 'auto'"
                           :datasource-id="cfg.datasourceId" :table-name="cfg.tableName" :schema-name="cfg.schemaName"
                           @update:model-value="cfg[f.key] = $event" />
        <SqlEditorField v-else-if="f.type === 'sqlEditor'"
                        :model-value="cfg[f.key] || {}" @update:model-value="cfg[f.key] = $event" />
      </div>
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="onSave">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import SourceTablePicker from './SourceTablePicker.vue'
import FieldMappingTable from './FieldMappingTable.vue'
import SqlEditorField from './SqlEditorField.vue'

const props = defineProps<{
  modelValue: boolean
  node: any
  fields: any[]
  datasources: any[]
  groupLabel?: string
  planned?: boolean
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'save', config: any): void }>()

const cfg = ref<any>({})

watch(() => props.node, (n) => {
  // 深拷贝编辑，避免中途取消脏数据
  cfg.value = JSON.parse(JSON.stringify(n?.data?.config || {}))
}, { immediate: true })

function mergeCfg(v: any) { cfg.value = { ...cfg.value, ...v } }
function setFields(key: string, val: string) {
  cfg.value[key] = String(val).split(',').map((s) => s.trim()).filter(Boolean)
}
function onSave() { emit('save', cfg.value); emit('update:modelValue', false) }
</script>

<style scoped>
.cfg-form { max-height: 60vh; overflow-y: auto; padding-right: 4px; }
.cfg-row { margin-bottom: 14px; }
.cfg-row label { display: block; font-size: 12px; color: #8295ad; margin-bottom: 5px; }
.cfg-info { display: flex; align-items: center; gap: 8px; padding-bottom: 10px; border-bottom: 1px solid rgba(255,255,255,0.07); margin-bottom: 16px; }
.info-tag { font-size: 11px; color: #cfdcec; background: rgba(64,158,255,0.12); padding: 2px 8px; border-radius: 8px; }
.info-kind { font-size: 11px; color: #5d7088; font-family: ui-monospace, Menlo, monospace; }
.planned-tag { font-size: 11px; color: #e6a23c; border: 1px solid #e6a23c44; padding: 1px 6px; border-radius: 4px; margin-left: auto; }
.mono :deep(textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; }
</style>
