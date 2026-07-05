<template>
  <div>
    <div class="dl-card">
      <div class="card-title"><span>资源监控 · 服务进程</span><span class="role-tag">系统管理员</span></div>
      <div class="kpi-grid" v-loading="loading">
        <div class="kpi"><div class="kv">{{ data.jvmUsedMB }}</div><div class="kl">JVM 已用 (MB)</div></div>
        <div class="kpi"><div class="kv">{{ data.jvmMaxMB }}</div><div class="kl">JVM 上限 (MB)</div></div>
        <div class="kpi"><div class="kv">{{ data.processors }}</div><div class="kl">CPU 核数</div></div>
      </div>
      <div style="margin-top:12px"><span class="muted">JVM 内存占用：</span><el-progress :percentage="data.jvmMaxMB ? Math.round(data.jvmUsedMB / data.jvmMaxMB * 100) : 0" :stroke-width="10" /></div>
    </div>
    <div class="dl-card" style="margin-top:14px">
      <div class="card-title"><span>StarRocks Backends (存储节点)</span><el-button link size="small" @click="load">刷新</el-button></div>
      <el-table :data="data.starrocksBackends || []" size="small" border max-height="420">
        <el-table-column v-for="c in beCols" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
      </el-table>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const data = ref<any>({}); const loading = ref(false)
const beCols = computed(() => { const arr = data.value.starrocksBackends || []; return arr[0] ? Object.keys(arr[0]).slice(0, 10) : [] })
async function load() { loading.value = true; try { data.value = await api.opsResource() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.kpi-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.kpi { background: var(--el-fill-color-light); border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 16px; text-align: center; }
.kv { font-size: 28px; font-weight: 700; color: var(--tech-primary); } .kl { font-size: 13px; color: var(--tech-text-muted); margin-top: 4px; }
.muted { color: var(--tech-text-muted); font-size: 13px; }
</style>
