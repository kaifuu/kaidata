<template>
  <div>
    <!-- KPI 卡片：数据流转全貌 -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <div class="dl-card kpi">
          <div class="kpi-icon" :style="{ background: k.color }">
            <el-icon :size="26"><component :is="k.icon" /></el-icon>
          </div>
          <div class="kpi-body">
            <div class="metric-value">{{ k.value }}</div>
            <div class="kpi-label">{{ k.label }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 近期告警（Flink 实时判定） -->
      <el-col :span="14">
        <div class="dl-card">
          <div class="card-title">
            <span>近期环境告警</span>
            <el-tag size="small" type="danger">实时流 · Flink</el-tag>
          </div>
          <el-table :data="alarms" size="small" stripe max-height="320">
            <el-table-column prop="device_id" label="设备" width="110" />
            <el-table-column prop="metric" label="指标" width="120">
              <template #default="{ row }">{{ metricName(row.metric) }}</template>
            </el-table-column>
            <el-table-column prop="value" label="实测" width="90" />
            <el-table-column label="合规区间" width="130">
              <template #default="{ row }">{{ row.min_val }} ~ {{ row.max_val }}</template>
            </el-table-column>
            <el-table-column prop="severity" label="级别" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.severity === 'CRITICAL' ? 'danger' : 'warning'">
                  {{ row.severity }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="ts" label="时间" />
          </el-table>
        </div>
      </el-col>

      <!-- 环境合规达标率（Spark 汇总） -->
      <el-col :span="10">
        <div class="dl-card">
          <div class="card-title">
            <span>环境合规达标率</span>
            <el-tag size="small" type="success">批处理 · Spark</el-tag>
          </div>
          <el-table :data="compliance" size="small" max-height="320">
            <el-table-column prop="device_id" label="设备" width="100" />
            <el-table-column prop="metric" label="指标" width="100">
              <template #default="{ row }">{{ metricName(row.metric) }}</template>
            </el-table-column>
            <el-table-column label="达标率">
              <template #default="{ row }">
                <el-progress
                  :percentage="Number(row.compliance_rate)"
                  :status="Number(row.compliance_rate) >= 95 ? 'success' : 'exception'"
                />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>

    <div class="flow-hint">
      <el-icon><InfoFilled /></el-icon>
      数据流：采集模拟器 → Kafka 总线 → Flink 实时入仓 / Spark 批分层加工 → StarRocks 数仓 → 本看板
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { api, type AlarmRow } from '@/api'

const overview = ref<Record<string, number>>({})
const alarms = ref<AlarmRow[]>([])
const compliance = ref<any[]>([])

const kpis = computed(() => [
  { label: '环境记录(ODS)', value: overview.value.envRecords ?? 0, icon: 'Monitor', color: '#409eff' },
  { label: '环境告警', value: overview.value.alarmCount ?? 0, icon: 'WarningFilled', color: '#f56c6c' },
  { label: '批次完工(ODS)', value: overview.value.batchCount ?? 0, icon: 'Connection', color: '#67c23a' },
  { label: '检验结果(ODS)', value: overview.value.qcCount ?? 0, icon: 'Checked', color: '#e6a23c' }
])

const metricName = (m: string) => ({ temp: '温度', humidity: '湿度', diffPressure: '压差' }[m] ?? m)

async function refresh() {
  try {
    const [ov, al, cp] = await Promise.all([api.overview(), api.envAlarms(8), api.compliance()])
    overview.value = ov
    alarms.value = al
    compliance.value = cp
  } catch (e) {
    // 数仓未就绪时静默（首次启动期间）
  }
}

let timer: number
onMounted(() => {
  refresh()
  timer = window.setInterval(refresh, 10000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.kpi { display: flex; align-items: center; gap: 14px; }
.kpi-icon { width: 52px; height: 52px; border-radius: 10px; color: #fff; display: flex; align-items: center; justify-content: center; }
.kpi-label { color: #909399; font-size: 13px; margin-top: 4px; }
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.flow-hint { color: #909399; font-size: 13px; margin-top: 8px; display: flex; align-items: center; gap: 6px; }
</style>
