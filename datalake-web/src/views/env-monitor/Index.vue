<template>
  <div>
    <!-- 各设备最新读数（实时刷新） -->
    <div class="dl-card">
      <div class="card-title">
        <span>设备最新读数</span>
        <el-tag size="small" type="danger">实时 · 每 5s 刷新</el-tag>
      </div>
      <el-row :gutter="12">
        <el-col :span="8" v-for="r in latest" :key="r.device_id + r.metric">
          <div class="reading" :class="inRange(r) ? 'ok' : 'alarm'">
            <div class="r-head">
              <span class="r-device">{{ r.device_id }}</span>
              <el-tag size="small" :type="inRange(r) ? 'success' : 'danger'">
                {{ metricName(r.metric) }}
              </el-tag>
            </div>
            <div class="r-value">{{ r.value }}</div>
            <div class="r-range">合规 {{ r.min_val }} ~ {{ r.max_val }}</div>
          </div>
        </el-col>
      </el-row>
    </div>

    <el-row :gutter="16">
      <!-- 历史趋势 -->
      <el-col :span="16">
        <div class="dl-card">
          <div class="card-title">
            <span>历史趋势</span>
            <div class="filters">
              <el-select v-model="selDevice" size="small" style="width: 130px">
                <el-option v-for="d in devices" :key="d" :label="d" :value="d" />
              </el-select>
              <el-select v-model="selMetric" size="small" style="width: 110px">
                <el-option label="温度" value="temp" />
                <el-option label="湿度" value="humidity" />
                <el-option label="压差" value="diffPressure" />
              </el-select>
              <el-button size="small" type="primary" @click="loadHistory">查询</el-button>
            </div>
          </div>
          <v-chart class="chart" :theme="chartTheme" :option="historyOption" autoresize />
        </div>
      </el-col>

      <!-- 实时告警流 -->
      <el-col :span="8">
        <div class="dl-card">
          <div class="card-title"><span>实时告警</span></div>
          <div class="alarm-list">
            <div v-for="a in alarms" :key="a.device_id + a.metric + a.ts" class="alarm-item" :class="a.severity.toLowerCase()">
              <div class="a-top">
                <span class="a-device">{{ a.device_id }} · {{ metricName(a.metric) }}</span>
                <el-tag size="small" :type="a.severity === 'CRITICAL' ? 'danger' : 'warning'">{{ a.severity }}</el-tag>
              </div>
              <div class="a-val">实测 {{ a.value }}（合规 {{ a.min_val }}~{{ a.max_val }}）</div>
              <div class="a-ts">{{ a.ts }}</div>
            </div>
            <el-empty v-if="!alarms.length" description="暂无告警" :image-size="60" />
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { VChart } from '@/echarts'
import { api, type AlarmRow, type EnvLatestRow } from '@/api'
import { theme } from '@/theme'

const chartTheme = theme.chartTheme

const latest = ref<EnvLatestRow[]>([])
const alarms = ref<AlarmRow[]>([])
const history = ref<any[]>([])

const devices = computed(() => [...new Set(latest.value.map(r => r.device_id))].sort())
const selDevice = ref('')
const selMetric = ref('temp')

const metricName = (m: string) => ({ temp: '温度', humidity: '湿度', diffPressure: '压差' }[m] ?? m)
const inRange = (r: EnvLatestRow) => r.value >= r.min_val && r.value <= r.max_val

const historyOption = computed(() => {
  const rows = [...history.value].reverse() // 接口返回 DESC，图表按时间正序
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['实测值', '下限', '上限'] },
    grid: { left: 40, right: 20, top: 40, bottom: 50 },
    xAxis: { type: 'category', data: rows.map(r => fmt(r.ts)) },
    yAxis: { type: 'value' },
    series: [
      { name: '实测值', type: 'line', smooth: true, data: rows.map(r => r.value), itemStyle: { color: '#409eff' } },
      { name: '下限', type: 'line', data: rows.map(r => r.min_val), lineStyle: { type: 'dashed' }, itemStyle: { color: '#67c23a' } },
      { name: '上限', type: 'line', data: rows.map(r => (r.max_val > 900 ? null : r.max_val)), lineStyle: { type: 'dashed' }, itemStyle: { color: '#f56c6c' } }
    ]
  }
})

function fmt(ts: string) {
  const d = new Date(ts)
  return d.toLocaleTimeString('zh-CN', { hour12: false })
}

async function loadLatest() {
  try {
    latest.value = await api.envLatest()
    if (!selDevice.value && devices.value.length) selDevice.value = devices.value[0]
  } catch { /* ignore */ }
}
async function loadAlarms() {
  try { alarms.value = await api.envAlarms(20) } catch { /* ignore */ }
}
async function loadHistory() {
  if (!selDevice.value) return
  try { history.value = await api.envHistory(selDevice.value, selMetric.value, 60) } catch { /* ignore */ }
}

watch([selDevice, selMetric], loadHistory)

let timer: number
onMounted(() => {
  loadLatest()
  loadAlarms()
  loadHistory()
  timer = window.setInterval(() => { loadLatest(); loadAlarms() }, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.filters { display: flex; gap: 8px; }
.reading { border: 1px solid #ebeef5; border-radius: 8px; padding: 12px; margin-bottom: 12px; }
.reading.ok { border-left: 4px solid #67c23a; }
.reading.alarm { border-left: 4px solid #f56c6c; background: #fef0f0; }
.r-head { display: flex; justify-content: space-between; align-items: center; }
.r-device { font-weight: 600; }
.r-value { font-size: 26px; font-weight: 600; margin: 6px 0; }
.r-range { color: #909399; font-size: 12px; }
.chart { height: 340px; }
.alarm-list { max-height: 380px; overflow-y: auto; }
.alarm-item { border-left: 3px solid #e6a23c; padding: 8px 10px; margin-bottom: 8px; background: #fdf6ec; border-radius: 4px; }
.alarm-item.critical { border-left-color: #f56c6c; background: #fef0f0; }
.a-top { display: flex; justify-content: space-between; align-items: center; }
.a-device { font-weight: 600; font-size: 13px; }
.a-val { font-size: 13px; color: #606266; margin: 2px 0; }
.a-ts { font-size: 12px; color: #909399; }
</style>
