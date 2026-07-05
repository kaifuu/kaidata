<template>
  <div>
    <div class="dl-card">
      <div class="card-title"><span>集群管理 · 组件探活</span><el-button link size="small" @click="load">刷新</el-button></div>
      <div class="comp-grid" v-loading="loading">
        <div v-for="c in (data.components || [])" :key="c.name" class="comp" :class="{ ok: c.alive, down: !c.alive }">
          <div class="cn">{{ c.name }}</div>
          <div class="cs">{{ c.host }}:{{ c.port }}</div>
          <el-tag size="small" :type="c.alive ? 'success' : 'danger'">{{ c.alive ? '在线' : '离线' }}</el-tag>
        </div>
      </div>
    </div>
    <div class="dl-card" style="margin-top:14px">
      <div class="card-title"><span>StarRocks Frontends</span></div>
      <el-table :data="data.starrocksFe || []" size="small" border max-height="220">
        <el-table-column v-for="c in feCols" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
      </el-table>
    </div>
    <div class="dl-card" style="margin-top:14px">
      <div class="card-title"><span>StarRocks Backends</span></div>
      <el-table :data="data.starrocksBe || []" size="small" border max-height="280">
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
const feCols = computed(() => { const a = data.value.starrocksFe || []; return a[0] ? Object.keys(a[0]).slice(0, 8) : [] })
const beCols = computed(() => { const a = data.value.starrocksBe || []; return a[0] ? Object.keys(a[0]).slice(0, 10) : [] })
async function load() { loading.value = true; try { data.value = await api.opsCluster() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.comp-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.comp { border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 14px; text-align: center; }
.comp.ok { border-color: var(--tech-success); } .comp.down { border-color: var(--tech-danger); }
.cn { font-weight: 600; margin-bottom: 4px; } .cs { font-size: 12px; color: var(--tech-text-muted); margin-bottom: 8px; }
</style>
