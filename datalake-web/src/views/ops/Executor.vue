<template>
  <div>
    <div class="dl-card">
      <div class="card-title"><span>执行器管理 · 在线调度任务</span><el-button link size="small" @click="load">刷新</el-button></div>
      <div class="kpi-grid" v-loading="loading">
        <div class="kpi"><div class="kv">{{ (data.profileOnline || []).length }}</div><div class="kl">在线探查任务</div></div>
        <div class="kpi"><div class="kv">{{ (data.workflowOnline || []).length }}</div><div class="kl">在线工作流</div></div>
        <div class="kpi"><div class="kv">{{ data.registeredAdapters ?? 0 }}</div><div class="kl">已注册适配器</div></div>
      </div>
    </div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:14px">
      <div class="dl-card">
        <div class="card-title"><span>在线探查任务（周期执行）</span></div>
        <el-table :data="data.profileOnline || []" size="small" border max-height="320">
          <el-table-column prop="name" label="任务" min-width="140" />
          <el-table-column prop="cron" label="周期(秒)" width="100" />
          <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag size="small" type="success">{{ row.status }}</el-tag></template></el-table-column>
        </el-table>
      </div>
      <div class="dl-card">
        <div class="card-title"><span>在线工作流（周期执行）</span></div>
        <el-table :data="data.workflowOnline || []" size="small" border max-height="320">
          <el-table-column prop="name" label="工作流" min-width="140" />
          <el-table-column prop="cron" label="周期(秒)" width="100" />
          <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag size="small" type="success">{{ row.status }}</el-tag></template></el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const data = ref<any>({}); const loading = ref(false)
async function load() { loading.value = true; try { data.value = await api.opsExecutors() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.kpi-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.kpi { background: var(--el-fill-color-light); border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 16px; text-align: center; }
.kv { font-size: 28px; font-weight: 700; color: var(--tech-primary); } .kl { font-size: 13px; color: var(--tech-text-muted); margin-top: 4px; }
</style>
