<template>
  <div>
    <div class="dl-card">
      <div class="card-title"><span>数据概览</span><span class="role-tag">系统管理员</span></div>
      <div class="kpi-grid" v-loading="loading">
        <div class="kpi"><div class="kv">{{ data.datasources ?? 0 }}</div><div class="kl">数据源</div></div>
        <div class="kpi"><div class="kv">{{ data.metaTables ?? 0 }}</div><div class="kl">元数据表</div></div>
        <div class="kpi"><div class="kv">{{ data.assets ?? 0 }}</div><div class="kl">资产总数</div></div>
        <div class="kpi"><div class="kv">{{ data.assetsApproved ?? 0 }}</div><div class="kl">资产已通过</div></div>
        <div class="kpi"><div class="kv">{{ data.profileJobs ?? 0 }}</div><div class="kl">探查任务</div></div>
        <div class="kpi"><div class="kv">{{ data.qualityRules ?? 0 }}</div><div class="kl">质量规则</div></div>
        <div class="kpi"><div class="kv">{{ data.workflows ?? 0 }}</div><div class="kl">工作流</div></div>
        <div class="kpi"><div class="kv">{{ data.scripts ?? 0 }}</div><div class="kl">数据开发脚本</div></div>
      </div>
    </div>
    <div class="dl-card" style="margin-top:14px">
      <div class="card-title"><span>分布</span></div>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px">
        <div><div class="muted" style="margin-bottom:6px">数据源类型分布</div><el-table :data="data.datasourceByType || []" size="small" border><el-table-column prop="type" label="类型" /><el-table-column prop="c" label="数量" width="100" /></el-table></div>
        <div><div class="muted" style="margin-bottom:6px">资产状态分布</div><el-table :data="data.assetByStatus || []" size="small" border><el-table-column prop="status" label="状态" /><el-table-column prop="c" label="数量" width="100" /></el-table></div>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const data = ref<any>({}); const loading = ref(false)
async function load() { loading.value = true; try { data.value = await api.opsOverview() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.kpi { background: var(--el-fill-color-light); border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 16px; text-align: center; }
.kv { font-size: 28px; font-weight: 700; color: var(--tech-primary); }
.kl { font-size: 13px; color: var(--tech-text-muted); margin-top: 4px; }
.muted { color: var(--tech-text-muted); font-size: 13px; }
</style>
