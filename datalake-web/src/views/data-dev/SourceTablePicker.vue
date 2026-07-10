<template>
  <div class="stp">
    <el-select :model-value="mv.datasourceId" placeholder="选择数据源" filterable size="small" @change="onDsChange" style="width:100%">
      <el-option v-for="d in datasources" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
    </el-select>
    <el-select v-if="mv.datasourceId" :model-value="mv.tableName" placeholder="选择表" filterable size="small"
               :loading="tblLoading" @change="onTblChange" style="width:100%;margin-top:6px">
      <el-option v-for="t in tables" :key="t.name" :label="t.schema_name ? `${t.schema_name}.${t.name}` : t.name" :value="t.name" />
    </el-select>
    <div v-if="selectMode === 'columns' && mv.tableName" class="stp-row">
      <el-button size="small" :loading="colLoading" @click="fetchCols">获取字段</el-button>
      <span class="stp-hint">{{ selFields.length }}/{{ cols.length }} 字段</span>
      <el-button v-if="cols.length" link size="small" @click="selAll">全选</el-button>
      <el-button v-if="cols.length" link size="small" @click="selNone">清空</el-button>
    </div>
    <div v-if="cols.length && selectMode === 'columns'" class="stp-cols">
      <el-checkbox-group v-model="selFields" @change="onFieldsChange">
        <el-checkbox v-for="c in cols" :key="c.name" :label="c.name">
          {{ c.name }}<span class="stp-type">{{ c.type }}</span>
        </el-checkbox>
      </el-checkbox-group>
    </div>
    <div v-if="!mv.datasourceId" class="stp-tip">先选择数据源</div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'

const props = defineProps<{ modelValue: any; datasources: any[]; selectMode: 'table' | 'columns' }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: any): void }>()

const mv = computed(() => props.modelValue || {})
const tables = ref<any[]>([])
const tblLoading = ref(false)
const cols = ref<any[]>([])
const colLoading = ref(false)
const selFields = ref<string[]>([])

// 数据源切换 → 加载表列表
watch(() => props.modelValue?.datasourceId, async (dsId) => {
  if (!dsId) { tables.value = []; cols.value = []; return }
  tblLoading.value = true
  try { tables.value = await api.daSourceTables(dsId) } catch { tables.value = [] }
  finally { tblLoading.value = false }
}, { immediate: true })

// 表切换 → 同步已选字段（懒加载列，点"获取字段"才拉）
watch(() => props.modelValue?.tableName, () => {
  selFields.value = Array.isArray(props.modelValue?.fields) ? [...props.modelValue.fields] : []
  cols.value = []
}, { immediate: true })

function onDsChange(v: number) {
  emit('update:modelValue', { ...props.modelValue, datasourceId: v, tableName: '', schemaName: '', fields: [] })
}
function onTblChange(v: string) {
  const t = tables.value.find((x) => x.name === v)
  emit('update:modelValue', { ...props.modelValue, tableName: v, schemaName: t?.schema_name || '', fields: [] })
}
async function fetchCols() {
  if (!mv.value.datasourceId || !mv.value.tableName) return
  colLoading.value = true
  try {
    cols.value = await api.daSourceColumns(mv.value.datasourceId, mv.value.tableName, mv.value.schemaName)
    selFields.value = cols.value.map((c: any) => c.name)   // 默认全选
    onFieldsChange(selFields.value)
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { colLoading.value = false }
}
function onFieldsChange(v: any) {
  emit('update:modelValue', { ...props.modelValue, fields: [...v] })
}
function selAll() { selFields.value = cols.value.map((c: any) => c.name); onFieldsChange(selFields.value) }
function selNone() { selFields.value = []; onFieldsChange([]) }
</script>

<style scoped>
.stp { width: 100%; }
.stp-row { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.stp-hint { font-size: 12px; color: #8295ad; }
.stp-cols { margin-top: 6px; max-height: 180px; overflow: auto; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.07); border-radius: 6px; padding: 6px 10px; }
.stp-cols :deep(.el-checkbox) { display: flex; margin-right: 0; color: #cfdcec; }
.stp-cols :deep(.el-checkbox__label) { font-size: 12px; }
.stp-type { color: #5d7088; font-size: 11px; margin-left: 6px; }
.stp-tip { color: #5d7088; font-size: 12px; margin-top: 6px; }
</style>
