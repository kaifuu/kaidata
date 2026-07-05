import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '@/auth'

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/login/Index.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('@/layout/index.vue'),
    redirect: '/portal',
    children: [
      { path: 'portal', name: 'Portal', component: () => import('@/views/portal/Home.vue'), meta: { title: '数据总览', icon: 'DataBoard' } },
      { path: 'env', name: 'Env', component: () => import('@/views/env-monitor/Index.vue'), meta: { title: '环境监控(实时)', icon: 'Monitor' } },
      { path: 'batch', name: 'Batch', component: () => import('@/views/batch-trace/Index.vue'), meta: { title: '批次质量追溯', icon: 'Connection' } },
      { path: 'board', name: 'Board', component: () => import('@/views/production-board/Index.vue'), meta: { title: '生产效能看板', icon: 'TrendCharts' } },
      { path: 'system/user', name: 'SysUser', component: () => import('@/views/system/User.vue'), meta: { title: '用户管理', icon: 'User' } },
      { path: 'system/org', name: 'SysOrg', component: () => import('@/views/system/Org.vue'), meta: { title: '组织管理', icon: 'OfficeBuilding' } },
      { path: 'system/tenant', name: 'SysTenant', component: () => import('@/views/system/Tenant.vue'), meta: { title: '租户管理', icon: 'Files' } },
      { path: 'system/role', name: 'SysRole', component: () => import('@/views/system/Role.vue'), meta: { title: '角色管理', icon: 'UserFilled' } },
      { path: 'system/menu', name: 'SysMenu', component: () => import('@/views/system/Menu.vue'), meta: { title: '菜单管理', icon: 'Menu' } },
      { path: 'system/log', name: 'SysLog', component: () => import('@/views/system/Log.vue'), meta: { title: '日志管理', icon: 'Document' } },
      // 数据接入
      { path: 'data-access/source', name: 'DaSource', component: () => import('@/views/data-access/Source.vue'), meta: { title: '数据源管理', icon: 'Connection' } },
      { path: 'data-access/file', name: 'DaFile', component: () => import('@/views/data-access/File.vue'), meta: { title: '文件管理', icon: 'FolderOpened' } },
      { path: 'data-access/offline', name: 'DaOffline', component: () => import('@/views/data-access/Offline.vue'), meta: { title: '离线数据接入', icon: 'Download' } },
      { path: 'data-access/stream', name: 'DaStream', component: () => import('@/views/data-access/Stream.vue'), meta: { title: '实时数据接入', icon: 'VideoPlay' } },
      { path: 'data-access/profile', name: 'DaProfile', component: () => import('@/views/data-access/Profile.vue'), meta: { title: '数据探查', icon: 'Search' } },
      // 数据治理
      { path: 'data-gov/std', name: 'GovStd', component: () => import('@/views/data-gov/Std.vue'), meta: { title: '数据标准', icon: 'Coin' } },
      { path: 'data-gov/model', name: 'GovModel', component: () => import('@/views/data-gov/Model.vue'), meta: { title: '数据模型', icon: 'Share' } },
      { path: 'data-gov/wh', name: 'GovWh', component: () => import('@/views/data-gov/Wh.vue'), meta: { title: '数据仓库', icon: 'Files' } },
      { path: 'data-gov/quality', name: 'GovQuality', component: () => import('@/views/data-gov/Quality.vue'), meta: { title: '数据质量', icon: 'CircleCheck' } },
      { path: 'data-gov/meta', name: 'GovMeta', component: () => import('@/views/data-gov/Meta.vue'), meta: { title: '元数据', icon: 'Document' } },
      { path: 'data-gov/tag', name: 'GovTag', component: () => import('@/views/data-gov/Tag.vue'), meta: { title: '数据标签', icon: 'PriceTag' } },
      { path: 'data-gov/master', name: 'GovMaster', component: () => import('@/views/data-gov/Master.vue'), meta: { title: '主数据', icon: 'Box' } },
      // 数据开发
      { path: 'data-dev/func', name: 'DevFunc', component: () => import('@/views/data-dev/Func.vue'), meta: { title: '函数管理', icon: 'Operation' } },
      { path: 'data-dev/script', name: 'DevScript', component: () => import('@/views/data-dev/Script.vue'), meta: { title: '数据开发', icon: 'EditPen' } },
      { path: 'data-dev/export', name: 'DevExport', component: () => import('@/views/data-dev/Export.vue'), meta: { title: '数据接出', icon: 'Promotion' } },
      { path: 'data-dev/workflow', name: 'DevWorkflow', component: () => import('@/views/data-dev/Workflow.vue'), meta: { title: '工作流', icon: 'Connection' } },
      // 数据资产
      { path: 'asset/catalog', name: 'AssetCatalog', component: () => import('@/views/asset/Catalog.vue'), meta: { title: '资产编目', icon: 'Files' } },
      { path: 'asset/mount', name: 'AssetMount', component: () => import('@/views/asset/Mount.vue'), meta: { title: '资产挂载', icon: 'Link' } },
      { path: 'asset/audit', name: 'AssetAudit', component: () => import('@/views/asset/Audit.vue'), meta: { title: '资产审核', icon: 'Checked' } },
      // 运维中心
      { path: 'ops/query', name: 'OpsQuery', component: () => import('@/views/ops/Query.vue'), meta: { title: '交互式分析', icon: 'Search' } },
      { path: 'ops/overview', name: 'OpsOverview', component: () => import('@/views/ops/Overview.vue'), meta: { title: '数据概览', icon: 'DataBoard' } },
      { path: 'ops/tasks', name: 'OpsTasks', component: () => import('@/views/ops/Tasks.vue'), meta: { title: '任务中心', icon: 'List' } },
      { path: 'ops/task-stats', name: 'OpsTaskStats', component: () => import('@/views/ops/TaskStats.vue'), meta: { title: '任务概览', icon: 'TrendCharts' } },
      { path: 'ops/resource', name: 'OpsResource', component: () => import('@/views/ops/Resource.vue'), meta: { title: '资源监控', icon: 'Odometer' } },
      { path: 'ops/cluster', name: 'OpsCluster', component: () => import('@/views/ops/Cluster.vue'), meta: { title: '集群管理', icon: 'Cpu' } },
      { path: 'ops/executor', name: 'OpsExecutor', component: () => import('@/views/ops/Executor.vue'), meta: { title: '执行器管理', icon: 'Setting' } },
      { path: 'ops/connector', name: 'OpsConnector', component: () => import('@/views/ops/Connector.vue'), meta: { title: '连接器管理', icon: 'Connection' } },
      // 数据安全
      { path: 'security/std', name: 'SecStd', component: () => import('@/views/security/Std.vue'), meta: { title: '安全标准', icon: 'Medal' } },
      { path: 'security/mask', name: 'SecMask', component: () => import('@/views/security/Mask.vue'), meta: { title: '数据脱敏', icon: 'Hide' } },
      { path: 'security/key', name: 'SecKey', component: () => import('@/views/security/Key.vue'), meta: { title: '密钥管理', icon: 'Key' } },
      { path: 'security/alert', name: 'SecAlert', component: () => import('@/views/security/Alert.vue'), meta: { title: '告警管理', icon: 'Bell' } },
      { path: 'security/ip', name: 'SecIp', component: () => import('@/views/security/Ip.vue'), meta: { title: '黑白名单', icon: 'Failed' } },
      { path: 'security/sensitive', name: 'SecSensitive', component: () => import('@/views/security/Sensitive.vue'), meta: { title: '敏感数据', icon: 'Warning' } },
      { path: 'security/perm', name: 'SecPerm', component: () => import('@/views/security/Perm.vue'), meta: { title: '数据权限', icon: 'Lock' } },
      // 数据服务
      { path: 'dservice/service', name: 'DsService', component: () => import('@/views/dservice/Service.vue'), meta: { title: '服务管理', icon: 'Connection' } },
      { path: 'dservice/stats', name: 'DsStats', component: () => import('@/views/dservice/Stats.vue'), meta: { title: '调用统计', icon: 'DataLine' } },
      // 数据集市
      { path: 'market/dataset', name: 'MarketDataset', component: () => import('@/views/market/Dataset.vue'), meta: { title: '数据集', icon: 'Files' } },
      { path: 'market/overview', name: 'MarketOverview', component: () => import('@/views/market/Overview.vue'), meta: { title: '资源概览', icon: 'DataAnalysis' } }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

// 登录闸门：未登录访问受保护页 → 跳登录并记录来源
router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!auth.isAuthed()) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

router.afterEach((to) => { document.title = `${to.meta.title || ''} - 数据中台` })
export default router
