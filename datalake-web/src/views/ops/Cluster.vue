<template>
  <div class="cl-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Connection /></el-icon></span>
        <div>
          <div class="page-title">集群管理</div>
          <div class="page-sub">组件探活 · StarRocks FE / BE 节点</div>
        </div>
      </div>
      <div class="head-right">
        <span class="kpi-mini">组件 <b class="ok">{{ compOnline }}</b>/{{ compTotal }}</span>
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- 组件探活 -->
    <div class="dl-card ov-card">
      <div class="card-head">
        <span class="card-head-title">组件探活</span>
        <span class="count-badge">在线 <b class="ok">{{ compOnline }}</b> / {{ compTotal }}</span>
      </div>
      <div class="comp-grid" v-loading="loading">
        <div v-for="c in (data.components || [])" :key="c.name" class="comp" :class="{ ok: c.alive, down: !c.alive }">
          <div class="comp-top">
            <span class="comp-ic"><el-icon><component :is="compIcon(c.name)" /></el-icon></span>
            <span class="cn">{{ c.name }}</span>
            <span class="st-pill" :class="c.alive ? 'success' : 'danger'"><i class="dot" />{{ c.alive ? '在线' : '离线' }}</span>
          </div>
          <div class="cs">{{ c.host }}:{{ c.port }}</div>
        </div>
        <div v-if="!(data.components || []).length && !loading" class="muted" style="padding:12px 0">暂无组件</div>
      </div>
    </div>

    <!-- FE / BE -->
    <el-row :gutter="14">
      <el-col :span="12">
        <div class="dl-card ov-card">
          <div class="card-head"><span class="card-head-title">StarRocks Frontends</span><span class="count-badge">共 <b>{{ (data.starrocksFe || []).length }}</b> 个</span></div>
          <el-table :data="data.starrocksFe || []" size="small" stripe max-height="260" v-loading="loading">
            <el-table-column v-for="c in feCols" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
            <template #empty><div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>暂无 FE 节点</div></div></template>
          </el-table>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="dl-card ov-card">
          <div class="card-head"><span class="card-head-title">StarRocks Backends</span><span class="count-badge">共 <b>{{ (data.starrocksBe || []).length }}</b> 个</span></div>
          <el-table :data="data.starrocksBe || []" size="small" stripe max-height="260" v-loading="loading">
            <el-table-column v-for="c in beCols" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
            <template #empty><div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>暂无 BE 节点</div></div></template>
          </el-table>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Connection, Coin, ChatDotRound, Lightning, Box, FolderOpened, DataLine } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const data = ref<any>({})
const loading = ref(false)

const COMP_ICON: Record<string, any> = { StarRocks: Coin, Kafka: ChatDotRound, Flink: Lightning, MinIO: Box }
function compIcon(name: string) { return COMP_ICON[name] || DataLine }

const comps = computed<any[]>(() => data.value.components || [])
const compTotal = computed(() => comps.value.length)
const compOnline = computed(() => comps.value.filter((c: any) => c.alive).length)

const feCols = computed(() => { const a = data.value.starrocksFe || []; return a[0] ? Object.keys(a[0]).slice(0, 8) : [] })
const beCols = computed(() => { const a = data.value.starrocksBe || []; return a[0] ? Object.keys(a[0]).slice(0, 10) : [] })

async function load() { loading.value = true; try { data.value = await api.opsCluster() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>

<style scoped>
.cl-page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 3px 10px; border-radius: 12px; background: var(--el-fill-color-light); }
.kpi-mini { font-size: 12px; color: var(--tech-text-muted); }
.kpi-mini b { font-size: 14px; margin: 0 2px; }
.kpi-mini b.ok { color: var(--tech-success); }
.ov-card { padding: 14px; }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.card-head .count-badge { margin-left: auto; }
.card-head .count-badge .ok { color: var(--tech-success); }
.comp-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; }
.comp { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 14px; background: var(--tech-bg-2); transition: border-color .15s; }
.comp.ok { border-color: color-mix(in srgb, var(--tech-success) 45%, transparent); }
.comp.down { border-color: color-mix(in srgb, var(--tech-danger) 45%, transparent); }
.comp-top { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.comp-ic { width: 30px; height: 30px; border-radius: 7px; display: inline-flex; align-items: center; justify-content: center; color: var(--tech-primary); background: color-mix(in srgb, var(--tech-primary) 14%, transparent); font-size: 16px; }
.cn { font-weight: 700; color: var(--tech-text); flex: 1; }
.cs { font-size: 12px; color: var(--tech-text-muted); font-family: ui-monospace, Menlo, monospace; }
.st-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; padding: 2px 8px; border-radius: 10px; }
.st-pill .dot { width: 6px; height: 6px; border-radius: 50%; }
.st-pill.success { color: var(--tech-success); background: color-mix(in srgb, var(--tech-success) 14%, transparent); }
.st-pill.success .dot { background: var(--tech-success); }
.st-pill.danger { color: var(--tech-danger); background: color-mix(in srgb, var(--tech-danger) 14%, transparent); }
.st-pill.danger .dot { background: var(--tech-danger); }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }
.muted { color: var(--tech-text-muted); font-size: 13px; }
</style>
