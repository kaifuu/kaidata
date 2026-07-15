<template>
  <div class="ex-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><SetUp /></el-icon></span>
        <div>
          <div class="page-title">执行器管理</div>
          <div class="page-sub">在线调度任务（周期执行）· 适配器注册</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- KPI -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">运行概览</span><span class="count-badge">实时</span></div>
      <div class="kpi-grid" v-loading="loading">
        <div class="kpi" style="--accent: var(--tech-warn)"><span class="kpi-ic"><el-icon><Search /></el-icon></span><div class="kpi-num">{{ profileN }}</div><div class="kpi-label">在线探查任务</div></div>
        <div class="kpi" style="--accent: var(--tech-primary-2)"><span class="kpi-ic"><el-icon><Share /></el-icon></span><div class="kpi-num">{{ workflowN }}</div><div class="kpi-label">在线工作流</div></div>
        <div class="kpi" style="--accent: var(--tech-success)"><span class="kpi-ic"><el-icon><CircleCheck /></el-icon></span><div class="kpi-num">{{ profileN + workflowN }}</div><div class="kpi-label">合计在线</div></div>
        <div class="kpi" style="--accent: var(--tech-accent)"><span class="kpi-ic"><el-icon><Connection /></el-icon></span><div class="kpi-num">{{ data.registeredAdapters ?? 0 }}</div><div class="kpi-label">已注册适配器</div></div>
      </div>
    </div>

    <!-- 在线任务两栏 -->
    <el-row :gutter="14">
      <el-col :span="12">
        <div class="dl-card ov-card">
          <div class="card-head"><span class="card-head-title">在线探查任务</span><span class="count-badge">共 <b>{{ profileN }}</b> 个</span></div>
          <el-table :data="data.profileOnline || []" size="small" stripe max-height="340" v-loading="loading">
            <el-table-column label="任务" min-width="150"><template #default="{ row }"><span class="task-name">{{ row.name }}</span></template></el-table-column>
            <el-table-column label="周期" width="110"><template #default="{ row }"><span class="cron-on"><el-icon><Clock /></el-icon>&nbsp;每 {{ row.cron }}s</span></template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" type="success" effect="light">{{ row.status }}</el-tag></template></el-table-column>
            <template #empty><div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>暂无在线探查任务</div></div></template>
          </el-table>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="dl-card ov-card">
          <div class="card-head"><span class="card-head-title">在线工作流</span><span class="count-badge">共 <b>{{ workflowN }}</b> 个</span></div>
          <el-table :data="data.workflowOnline || []" size="small" stripe max-height="340" v-loading="loading">
            <el-table-column label="工作流" min-width="150"><template #default="{ row }"><span class="task-name">{{ row.name }}</span></template></el-table-column>
            <el-table-column label="周期" width="110"><template #default="{ row }"><span class="cron-on"><el-icon><Clock /></el-icon>&nbsp;每 {{ row.cron }}s</span></template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" type="success" effect="light">{{ row.status }}</el-tag></template></el-table-column>
            <template #empty><div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>暂无在线工作流</div></div></template>
          </el-table>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, SetUp, Search, Share, CircleCheck, Connection, Clock, FolderOpened } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const data = ref<any>({})
const loading = ref(false)
const profileN = computed(() => (data.value.profileOnline || []).length)
const workflowN = computed(() => (data.value.workflowOnline || []).length)

async function load() { loading.value = true; try { data.value = await api.opsExecutors() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>

<style scoped>
.ex-page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 3px 10px; border-radius: 12px; background: var(--el-fill-color-light); }
.ov-card { padding: 14px; }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.card-head .count-badge { margin-left: auto; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.kpi { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 16px; border-radius: 10px; border: 1px solid var(--tech-panel-border); background: var(--tech-bg-2); }
.kpi-ic { width: 34px; height: 34px; border-radius: 8px; display: inline-flex; align-items: center; justify-content: center; color: var(--accent); background: color-mix(in srgb, var(--accent) 15%, transparent); font-size: 18px; }
.kpi-num { font-size: 26px; font-weight: 700; color: var(--tech-text); line-height: 1; }
.kpi-label { font-size: 12px; color: var(--tech-text-muted); }
.task-name { font-weight: 600; color: var(--tech-text); }
.cron-on { display: inline-flex; align-items: center; font-size: 12px; color: var(--tech-success); }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }
</style>
