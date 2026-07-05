// ECharts 按需注册 + 双主题（tech-dark 霓虹 / tech-light DIFY 风格）
// 各视图统一从这里引入 VChart，并用 theme.chartTheme 绑定 :theme，随全局主题切换。
import { use, registerTheme } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, GaugeChart, LineChart, PieChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'

use([
  CanvasRenderer,
  LineChart,
  BarChart,
  PieChart,
  GaugeChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent
])

// 暗色霓虹主题
registerTheme('tech-dark', {
  backgroundColor: 'transparent',
  color: ['#00e0ff', '#2f6bff', '#7c5cff', '#2ee6a6', '#ffb020', '#ff4d6d', '#22d3ee'],
  textStyle: { color: '#aebfe2' },
  title: { textStyle: { color: '#d6e6ff' }, subtextStyle: { color: '#7f93bf' } },
  legend: { textStyle: { color: '#aebfe2' } },
  tooltip: {
    backgroundColor: 'rgba(10,20,48,0.92)',
    borderColor: 'rgba(0,224,255,0.4)',
    textStyle: { color: '#d6e6ff' }
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: 'rgba(0,224,255,0.35)' } },
    axisTick: { lineStyle: { color: 'rgba(0,224,255,0.25)' } },
    axisLabel: { color: '#7f93bf' },
    splitLine: { lineStyle: { color: 'rgba(0,224,255,0.08)' } }
  },
  valueAxis: {
    axisLine: { lineStyle: { color: 'rgba(0,224,255,0.35)' } },
    axisTick: { lineStyle: { color: 'rgba(0,224,255,0.25)' } },
    axisLabel: { color: '#7f93bf' },
    splitLine: { lineStyle: { color: 'rgba(0,224,255,0.08)' } }
  },
  line: { itemStyle: { borderWidth: 2 }, lineStyle: { width: 2 }, symbolSize: 6, symbol: 'circle', smooth: true }
})

// 浅色 DIFY 风格主题
registerTheme('tech-light', {
  backgroundColor: 'transparent',
  color: ['#1557ef', '#4f46e5', '#7c5cff', '#16b364', '#f79009', '#f04438', '#0ba5ec'],
  textStyle: { color: '#475467' },
  title: { textStyle: { color: '#101828' }, subtextStyle: { color: '#667085' } },
  legend: { textStyle: { color: '#475467' } },
  tooltip: {
    backgroundColor: 'rgba(255,255,255,0.96)',
    borderColor: '#e9ebf0',
    borderWidth: 1,
    textStyle: { color: '#101828' },
    extraCssText: 'box-shadow: 0 4px 16px rgba(16,24,40,0.12);'
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#e9ebf0' } },
    axisTick: { lineStyle: { color: '#e9ebf0' } },
    axisLabel: { color: '#667085' },
    splitLine: { lineStyle: { color: '#f2f4f7' } }
  },
  valueAxis: {
    axisLine: { lineStyle: { color: '#e9ebf0' } },
    axisTick: { lineStyle: { color: '#e9ebf0' } },
    axisLabel: { color: '#667085' },
    splitLine: { lineStyle: { color: '#f2f4f7' } }
  },
  line: { itemStyle: { borderWidth: 2 }, lineStyle: { width: 2 }, symbolSize: 6, symbol: 'circle', smooth: true }
})

export { default as VChart } from 'vue-echarts'
