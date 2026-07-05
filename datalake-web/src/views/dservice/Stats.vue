<template>
  <div class="dl-card">
    <div class="card-title"><span>调用统计</span><el-button link size="small" @click="load">刷新</el-button></div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:14px" v-loading="loading">
      <div>
        <div class="muted" style="margin-bottom:6px">服务调用统计</div>
        <el-table :data="stats" size="small" border max-height="440">
          <el-table-column prop="code" label="服务" min-width="120" />
          <el-table-column prop="calls" label="调用数" width="80" />
          <el-table-column label="平均(ms)" width="90"><template #default="{ row }">{{ Math.round(row.avg_cost) }}</template></el-table-column>
          <el-table-column label="成功率" width="90"><template #default="{ row }">{{ row.calls > 0 ? Math.round(row.success / row.calls * 100) + '%' : '—' }}</template></el-table-column>
          <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ row.status }}</el-tag></template></el-table-column>
        </el-table>
      </div>
      <div>
        <div class="muted" style="margin-bottom:6px">最近调用日志</div>
        <el-table :data="logs" size="small" border max-height="440">
          <el-table-column prop="call_time" label="时间" width="155" />
          <el-table-column prop="caller" label="调用方" width="90" />
          <el-table-column prop="cost_ms" label="耗时(ms)" width="85" />
          <el-table-column prop="status" label="状态" width="70"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="ip" label="IP" width="110" />
        </el-table>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const stats = ref<any[]>([]); const logs = ref<any[]>([]); const loading = ref(false)
async function load() { loading.value = true; try { stats.value = await api.dsStats(); logs.value = await api.dsLogs() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.muted{color:var(--tech-text-muted);font-size:13px}</style>
