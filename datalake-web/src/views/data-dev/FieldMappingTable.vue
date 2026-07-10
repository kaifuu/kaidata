<template>
  <div class="fmt">
    <div class="fmt-bar">
      <el-button size="small" :loading="loading" @click="fetchAndMatch">获取目标字段并自动匹配</el-button>
      <el-button size="small" @click="addRow">+ 行</el-button>
    </div>
    <el-table :data="rows" size="small" border>
      <el-table-column :label="mappingType === 'key' ? '流字段' : '源字段'" min-width="120">
        <template #default="{ row }"><el-input v-model="row.source" size="small" placeholder="上游字段" /></template>
      </el-table-column>
      <el-table-column :label="mappingType === 'key' ? '目标关键字段' : '目标字段'" min-width="140">
        <template #default="{ row }">
          <el-select v-model="row.target" size="small" filterable placeholder="选字段" style="width:100%">
            <el-option v-for="c in targetCols" :key="c.name" :label="c.name" :value="c.name" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="" width="50">
        <template #default="{ $index }"><el-button link type="danger" size="small" @click="delRow($index)">✕</el-button></template>
      </el-table-column>
    </el-table>
    <div v-if="!targetCols.length" class="fmt-tip">先在上方选择目标表后点"获取目标字段"</div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'

const props = defineProps<{
  modelValue: any[]; mappingType: 'auto' | 'key'
  datasourceId?: number; tableName?: string; schemaName?: string
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: any[]): void }>()

const rows = ref<any[]>(Array.isArray(props.modelValue) ? props.modelValue.map((r) => ({ ...r })) : [])
const targetCols = ref<any[]>([])
const loading = ref(false)

watch(() => props.modelValue, (v) => {
  const arr = Array.isArray(v) ? v.map((r) => ({ ...r })) : []
  if (JSON.stringify(arr) !== JSON.stringify(rows.value)) rows.value = arr
})
watch(rows, () => emit('update:modelValue', rows.value.map((r) => ({ ...r }))), { deep: true })

async function fetchAndMatch() {
  if (!props.datasourceId || !props.tableName) { ElMessage.warning('先选择目标表'); return }
  loading.value = true
  try {
    targetCols.value = await api.daSourceColumns(props.datasourceId, props.tableName, props.schemaName)
    // 自动同名匹配：源字段为空的行，用目标字段名回填源
    const existing = new Map(rows.value.filter((r) => r.source).map((r) => [r.target, r]))
    const matched: any[] = []
    for (const c of targetCols.value) {
      const e = existing.get(c.name)
      matched.push({ source: e?.source || c.name, target: c.name })
    }
    rows.value = matched
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function addRow() { rows.value.push({ source: '', target: '' }) }
function delRow(i: number) { rows.value.splice(i, 1) }
</script>

<style scoped>
.fmt-bar { display: flex; gap: 8px; margin-bottom: 6px; }
.fmt-tip { color: #5d7088; font-size: 12px; margin-top: 6px; }
</style>
