<template>
  <div class="sef">
    <el-switch v-model="useSql" @change="emitVal" active-text="SQL编辑模式（自定义查询替代选字段）" />
    <template v-if="useSql">
      <el-input v-model="sql" type="textarea" :rows="4" class="mono"
                placeholder="SELECT col1, col2 FROM ods.t (单条语句；增量模式不加分号)" @input="emitVal" style="margin-top:6px" />
      <div class="sef-bar">
        <el-button size="small" @click="parseFields">获取字段</el-button>
        <span class="hint">{{ fieldsArr.length }} 字段：{{ fieldsArr.join(', ') }}</span>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{ modelValue: any }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: any): void }>()

const useSql = ref(!!props.modelValue?.useSql)
const sql = ref(props.modelValue?.sql || '')
const fieldsArr = ref<string[]>(Array.isArray(props.modelValue?.fields) ? [...props.modelValue.fields] : [])

watch(() => props.modelValue, (v) => {
  useSql.value = !!v?.useSql
  sql.value = v?.sql || ''
  fieldsArr.value = Array.isArray(v?.fields) ? [...v.fields] : []
})

function emitVal() {
  emit('update:modelValue', { useSql: useSql.value, sql: sql.value, fields: [...fieldsArr.value] })
}
// 简单解析 SELECT 与 FROM 之间的列名（逗号分隔，去别名）
function parseFields() {
  const s = sql.value || ''
  const m = s.match(/select\s+(.*?)\s+from/is)
  if (!m) { fieldsArr.value = []; emitVal(); return }
  fieldsArr.value = m[1].split(',').map((x) => x.trim().replace(/\s+as\s+.*$/i, '').replace(/^.*\./, '')).filter(Boolean)
  emitVal()
}
</script>

<style scoped>
.sef { width: 100%; }
.sef-bar { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.hint { font-size: 12px; color: #8295ad; }
.mono :deep(textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; }
</style>
