<template>
  <div>
    <el-row :gutter="16">
      <!-- 各物料产量 -->
      <el-col :span="12">
        <div class="dl-card">
          <div class="card-title"><span>各物料产量</span><el-tag size="small" type="success">ADS · Spark</el-tag></div>
          <v-chart class="chart" :theme="chartTheme" :option="qtyOption" autoresize />
        </div>
      </el-col>
      <!-- 各物料合格率 -->
      <el-col :span="12">
        <div class="dl-card">
          <div class="card-title"><span>各物料检验合格率</span></div>
          <v-chart class="chart" :theme="chartTheme" :option="passOption" autoresize />
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 批次占比 -->
      <el-col :span="12">
        <div class="dl-card">
          <div class="card-title"><span>批次分布</span></div>
          <v-chart class="chart" :theme="chartTheme" :option="pieOption" autoresize />
        </div>
      </el-col>
      <!-- 环境合规达标率（仪表盘取均值） -->
      <el-col :span="12">
        <div class="dl-card">
          <div class="card-title"><span>环境综合达标率</span></div>
          <v-chart class="chart" :theme="chartTheme" :option="gaugeOption" autoresize />
        </div>
      </el-col>
    </el-row>

    <div class="dl-card">
      <div class="card-title"><span>生产效能明细</span></div>
      <el-table :data="efficiency" size="small" stripe border>
        <el-table-column prop="material_code" label="物料编码" width="130" />
        <el-table-column prop="material_name" label="物料名称" />
        <el-table-column prop="total_batches" label="批次数" width="100" />
        <el-table-column prop="abnormal_batches" label="异常批次" width="100" />
        <el-table-column prop="total_quantity" label="总产量" width="110" />
        <el-table-column prop="avg_qc_result" label="平均检验值" width="120" />
        <el-table-column label="合格率">
          <template #default="{ row }">
            <el-progress :percentage="Number(row.pass_rate)" :status="Number(row.pass_rate) >= 95 ? 'success' : 'exception'" />
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!efficiency.length" class="empty-tip">暂无数据。运行 WarehouseBuildJob 后生成 ads_production_efficiency。</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { VChart } from '@/echarts'
import { api, type EfficiencyRow } from '@/api'
import { theme } from '@/theme'

const chartTheme = theme.chartTheme

const efficiency = ref<EfficiencyRow[]>([])
const compliance = ref<any[]>([])

const names = computed(() => efficiency.value.map(e => e.material_name || e.material_code))

const qtyOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 50, right: 20, top: 30, bottom: 30 },
  xAxis: { type: 'category', data: names.value },
  yAxis: { type: 'value', name: '产量' },
  series: [{ type: 'bar', data: efficiency.value.map(e => e.total_quantity), itemStyle: { color: '#409eff' }, barWidth: '40%' }]
}))

const passOption = computed(() => ({
  tooltip: { trigger: 'axis', formatter: '{b}: {c}%' },
  grid: { left: 50, right: 20, top: 30, bottom: 30 },
  xAxis: { type: 'category', data: names.value },
  yAxis: { type: 'value', max: 100, name: '合格率%' },
  series: [{
    type: 'bar',
    data: efficiency.value.map(e => e.pass_rate),
    barWidth: '40%',
    itemStyle: { color: (p: any) => (p.value >= 95 ? '#67c23a' : '#e6a23c') }
  }]
}))

const pieOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie',
    radius: ['40%', '70%'],
    data: efficiency.value.map(e => ({ name: e.material_name || e.material_code, value: e.total_batches }))
  }]
}))

const avgCompliance = computed(() => {
  if (!compliance.value.length) return 0
  const sum = compliance.value.reduce((s, r) => s + Number(r.compliance_rate), 0)
  return Math.round((sum / compliance.value.length) * 10) / 10
})
const gaugeOption = computed(() => ({
  series: [{
    type: 'gauge',
    progress: { show: true, width: 18 },
    axisLine: { lineStyle: { width: 18 } },
    detail: { valueAnimation: true, formatter: '{value}%', fontSize: 24 },
    data: [{ value: avgCompliance.value }]
  }]
}))

async function load() {
  try {
    const [eff, cp] = await Promise.all([api.productionEfficiency(), api.compliance()])
    efficiency.value = eff
    compliance.value = cp
  } catch { /* ignore */ }
}
onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.chart { height: 280px; }
.empty-tip { color: #909399; font-size: 13px; padding: 16px 0; text-align: center; }
</style>
