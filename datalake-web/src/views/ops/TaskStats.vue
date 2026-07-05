<template>
  <div class="dl-card">
    <div class="card-title"><span>任务概览 · 执行统计</span><span class="role-tag">系统管理员</span></div>
    <el-row :gutter="14" v-loading="loading">
      <el-col :span="8" v-for="(s, k) in stats" :key="k" style="margin-bottom:14px">
        <div class="stat-card">
          <div class="st-title">{{ label(k) }}</div>
          <div class="st-nums">
            <div><span class="n suc">{{ s.success }}</span><span class="lab">成功</span></div>
            <div><span class="n fail">{{ s.fail }}</span><span class="lab">失败</span></div>
            <div><span class="n tot">{{ s.total }}</span><span class="lab">总计</span></div>
          </div>
          <el-progress :percentage="rate(s)" :color="rate(s) > 90 ? '#16b364' : rate(s) > 60 ? '#f79009' : '#f04438'" :stroke-width="8" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const stats = ref<any>({}); const loading = ref(false)
const labels: any = { profile: '数据探查', quality: '数据质量', workflow: '工作流', export: '数据接出', script: '数据开发', offline: '离线接入' }
const label = (k: string) => labels[k] || k
const rate = (s: any) => s.total > 0 ? Math.round((s.success / s.total) * 100) : 0
async function load() { loading.value = true; try { stats.value = await api.opsTaskStats() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.stat-card { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 14px; background: var(--el-fill-color-light); }
.st-title { font-weight: 600; margin-bottom: 10px; color: var(--tech-text); }
.st-nums { display: flex; gap: 18px; margin-bottom: 10px; }
.st-nums .n { font-size: 24px; font-weight: 700; display: block; }
.st-nums .lab { font-size: 12px; color: var(--tech-text-muted); }
.n.suc { color: var(--tech-success); } .n.fail { color: var(--tech-danger); } .n.tot { color: var(--tech-primary); }
</style>
