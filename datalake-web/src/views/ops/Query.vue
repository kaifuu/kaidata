<template>
  <div class="dl-card">
    <div class="card-title"><span>交互式分析 · 运维查询工作台</span><span class="role-tag">系统管理员</span></div>
    <div class="bar">
      <el-select v-model="dsId" size="small" placeholder="数据源" filterable style="width:260px">
        <el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
      </el-select>
      <el-button size="small" type="success" :loading="running" @click="run">执行</el-button>
      <el-button size="small" @click="sql = 'SHOW DATABASES;'">库</el-button>
      <el-button size="small" @click="sql = 'SHOW TABLES FROM ods;'">表</el-button>
      <el-button size="small" @click="sql = 'SHOW ROUTINE LOAD;'">RoutineLoad</el-button>
    </div>
    <el-input v-model="sql" type="textarea" :rows="8" placeholder="SHOW DATABASES; / SELECT * FROM ods.your_table LIMIT 10;" style="font-family:monospace" />
    <div v-if="result" class="result">
      <span class="muted">{{ result.status }} · {{ result.rowsRead }} 行 · {{ result.msg || 'OK' }}</span>
      <el-table :data="result.rows" size="small" border max-height="340" v-if="result.rows && result.rows.length" style="margin-top:8px">
        <el-table-column v-for="c in result.columns" :key="c" :prop="c" :label="c" min-width="130" show-overflow-tooltip />
      </el-table>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const dsList = ref<any[]>([]); const dsId = ref<any>(null); const sql = ref('SHOW DATABASES;')
const running = ref(false); const result = ref<any>(null)
async function run() { if (!dsId.value || !sql.value) return ElMessage.warning('选数据源并写 SQL'); running.value = true; try { result.value = await api.opsQuery({ datasource_id: dsId.value, content: sql.value }) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { running.value = false } }
onMounted(async () => { try { dsList.value = await api.daSources(); const sr = dsList.value.find((d:any) => d.type === 'starrocks'); dsId.value = (sr || dsList.value[0])?.id || null } catch { /* */ } })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.bar { display: flex; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.result { margin-top: 10px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
</style>
