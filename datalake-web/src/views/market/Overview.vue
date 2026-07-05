<template>
  <div>
    <div class="dl-card">
      <div class="card-title"><span>资源概览 · 数据集市</span><el-button link size="small" @click="load">刷新</el-button></div>
      <div class="kpi-grid" v-loading="loading">
        <div class="kpi"><div class="kv">{{ data.serviceCount ?? 0 }}</div><div class="kl">开放接口</div></div>
        <div class="kpi"><div class="kv">{{ data.tableCount ?? 0 }}</div><div class="kl">库表资源</div></div>
        <div class="kpi"><div class="kv">{{ data.datasourceCount ?? 0 }}</div><div class="kl">数据源</div></div>
        <div class="kpi"><div class="kv">{{ data.cartCount ?? 0 }}</div><div class="kl">我的购物车</div></div>
      </div>
    </div>
    <div class="dl-card" style="margin-top:14px">
      <div class="card-title"><span>库表资源 · 按数据源分布</span></div>
      <el-table :data="data.byDs || []" size="small" border>
        <el-table-column prop="ds_id" label="数据源ID" width="120" />
        <el-table-column prop="c" label="表数量" width="120" />
        <el-table-column label="占比" min-width="200">
          <template #default="{ row }">
            <el-progress :percentage="data.tableCount ? Math.round(row.c / data.tableCount * 100) : 0" :stroke-width="10" />
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const data = ref<any>({}); const loading = ref(false)
async function load() { loading.value = true; try { data.value = await api.marketOverview() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.kpi { background: var(--el-fill-color-light); border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 18px; text-align: center; }
.kv { font-size: 30px; font-weight: 700; color: var(--tech-primary); } .kl { font-size: 13px; color: var(--tech-text-muted); margin-top: 4px; }
</style>
