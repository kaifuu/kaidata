import axios from 'axios'
import { auth } from './auth'

// 统一请求：经 vite 代理 /api → 服务层(8090) → StarRocks 数仓 / meta 元数据库
export const http = axios.create({ baseURL: '/api', timeout: 15000 })

// 请求拦截：自动携带令牌（除登录外所有接口都需鉴权）
http.interceptors.request.use((cfg) => {
  const t = auth.token()
  if (t) cfg.headers.Authorization = 'Bearer ' + t
  return cfg
})

// 响应拦截：401 清令牌并跳登录；403（三员越权）由调用处提示
http.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response && err.response.status === 401) {
      auth.clear()
      if (location.pathname !== '/login') location.href = '/login'
    }
    return Promise.reject(err)
  }
)

// 403 统一提示：调用处 catch 后可调
export function errMsg(e: any, def = '操作失败') {
  const msg = e?.response?.data?.message
  return msg || def
}

export interface EnvLatestRow { device_id: string; metric: string; value: number; min_val: number; max_val: number; ts: string }
export interface AlarmRow { device_id: string; metric: string; value: number; min_val: number; max_val: number; severity: string; ts: string }
export interface BatchQualityRow { batch_no: string; material_code: string; quantity: number; batch_status: string; qc_pass: boolean | number; qc_result: number; qc_spec: number; ts: string }
export interface EfficiencyRow { material_code: string; material_name: string; total_batches: number; abnormal_batches: number; total_quantity: number; avg_qc_result: number; pass_rate: number }

export interface MenuRow { id: number; parent_id: number; name: string; path: string; icon: string; perm: string; type: string; sort: number }
export interface UserRow { id: number; username: string; name: string; status: string; tenant_id?: number; org_id?: number; create_time: string; tenant_name?: string; org_name?: string; roles: string[] }
export interface RoleFullRow { id: number; code: string; name: string; menu_ids: number[]; user_ids: number[] }
export interface TenantRow { id: number; code: string; name: string; status: string; create_time?: string; org_count?: number; user_count?: number }
export interface OrgRow { id: number; tenant_id: number; parent_id: number; code: string; name: string; sort: number; create_time?: string; tenant_name?: string; user_count?: number; children?: OrgRow[] }
export interface LogRow { id: number; username: string; uri: string; method: string; params: string; result: string; ip: string; ts: string }

// ===== 数据接入 =====
export interface DataSourceType { code: string; driverAvailable: boolean; jarHint?: string }
export interface DataSourceRow { id: number; name: string; type: string; host: string; port: number; db_name: string; username: string; props?: string; status: string; tenant_id?: number; create_time?: string; update_time?: string }
export interface FilestoreRow { id: number; name: string; type: string; host: string; port: number; username: string; base_path: string; props?: string; status: string; create_time?: string }
export interface IngestedFileRow { id: number; store_id: number; path: string; name: string; size: number; file_type: string; target_table: string; rows_written: number; ingested: boolean; create_time: string }
export interface OfflineJobRow { id: number; name: string; source_ds_id: number; source_table: string; target_db: string; target_table: string; strategy: string; inc_column?: string; biz_key?: string; last_value?: string; column_map?: string; status: string; create_time?: string }
export interface OfflineRunRow { id: number; job_id: number; start_time: string; end_time: string; status: string; rows_read: number; rows_written: number; error_msg?: string; triggered_by?: string }
export interface StreamJobRow { id: number; name: string; type: string; source_ds_id?: number; source_query?: string; kafka_topic: string; target_db?: string; target_table?: string; columns_json?: string; schedule_cron?: string; status: string }
export interface StreamRunRow { id: number; job_id: number; start_time: string; end_time: string; status: string; rows_in: number; rows_out: number; error_msg?: string }
export interface RoutineLoadRow { Name: string; DbName: string; TableName: string; State: string; [k: string]: any }

// ===== 数据探查 =====
export interface ProfileJobRow { id: number; name: string; source_ds_id: number; target_db: string; first_create_table: boolean; alert_enabled: boolean; extra_columns?: string; cron?: string; status: string; create_time?:string }
export interface ProfileRunRow { id: number; job_id: number; start_time: string; end_time: string; status: string; tables_changed: number; tables_total: number; error_msg?: string; log_text?: string; triggered_by?: string }
export interface ProfileSnapshotRow { id: number; job_id: number; table_name: string; version_n: number; run_id: number; created_time: string }
export interface ProfileTableCfg { table_name: string; is_view: boolean; columns_config: string }

// 通用保存：无 id → POST 新增，有 id → PUT 更新
function save(path: string, body: any) {
  return (body.id ? http.put(path, body) : http.post(path, body)).then((r) => r.data)
}

export const api = {
  // 认证
  login: (username: string, password: string) =>
    http.post<{ token: string; user: any; menus: MenuRow[] }>('/auth/login', { username, password }).then((r) => r.data),
  authMenus: () => http.get<MenuRow[]>('/auth/menus').then((r) => r.data),

  // 数据
  overview: () => http.get('/portal/overview').then((r) => r.data),
  envLatest: () => http.get<EnvLatestRow[]>('/env/latest').then((r) => r.data),
  envAlarms: (limit = 50) => http.get<AlarmRow[]>('/env/alarms', { params: { limit } }).then((r) => r.data),
  envHistory: (deviceId: string, metric: string, limit = 100) =>
    http.get('/env/history', { params: { deviceId, metric, limit } }).then((r) => r.data),
  batchQuality: (batchNo?: string) =>
    http.get<BatchQualityRow[]>('/batch/quality', { params: batchNo ? { batchNo } : {} }).then((r) => r.data),
  compliance: () => http.get('/env/compliance').then((r) => r.data),
  productionEfficiency: () => http.get<EfficiencyRow[]>('/production/efficiency').then((r) => r.data),

  // ===== 系统管理：用户 [SYS_ADMIN] =====
  sysUsers: () => http.get<UserRow[]>('/system/user').then((r) => r.data),
  sysSaveUser: (body: any) => save('/system/user', body),
  sysDeleteUser: (id: number) => http.delete('/system/user', { params: { id } }).then((r) => r.data),

  // ===== 组织 [SYS_ADMIN] =====
  sysOrgs: (tenantId?: number) => http.get<OrgRow[]>('/system/org', { params: tenantId ? { tenantId } : {} }).then((r) => r.data),
  sysSaveOrg: (body: any) => save('/system/org', body),
  sysDeleteOrg: (id: number) => http.delete('/system/org', { params: { id } }).then((r) => r.data),

  // ===== 租户 [SYS_ADMIN] =====
  sysTenants: () => http.get<TenantRow[]>('/system/tenant').then((r) => r.data),
  sysSaveTenant: (body: any) => save('/system/tenant', body),
  sysDeleteTenant: (id: number) => http.delete('/system/tenant', { params: { id } }).then((r) => r.data),

  // ===== 角色 / 授权 [SEC_ADMIN] =====
  sysRoles: () => http.get<RoleFullRow[]>('/system/role').then((r) => r.data),
  sysSaveRole: (body: any) => save('/system/role', body),
  sysDeleteRole: (id: number) => http.delete('/system/role', { params: { id } }).then((r) => r.data),
  sysGrantMenus: (roleId: number, menuIds: number[]) => http.put('/system/role/menus', { roleId, menuIds }).then((r) => r.data),
  sysGrantUsers: (roleId: number, userIds: number[]) => http.put('/system/role/users', { roleId, userIds }).then((r) => r.data),

  // ===== 菜单 [SEC_ADMIN] =====
  sysMenus: () => http.get<MenuRow[]>('/system/menu').then((r) => r.data),
  sysSaveMenu: (body: any) => save('/system/menu', body),
  sysDeleteMenu: (id: number) => http.delete('/system/menu', { params: { id } }).then((r) => r.data),
  sysToggleMenu: (id: number) => http.post('/system/menu/toggle', null, { params: { id } }).then((r) => r.data),

  // ===== 日志 [AUDIT_ADMIN] =====
  sysLogs: (params: { username?: string; result?: string; keyword?: string; limit?: number } = {}) =>
    http.get<LogRow[]>('/system/log', { params }).then((r) => r.data),

  // ===== 数据源管理 [SYS_ADMIN] =====
  daSourceTypes: () => http.get<DataSourceType[]>('/data-access/source/types').then((r) => r.data),
  daSources: () => http.get<DataSourceRow[]>('/data-access/source/list').then((r) => r.data),
  daSourceDetail: (id: number) => http.get<DataSourceRow>('/data-access/source/detail', { params: { id } }).then((r) => r.data),
  daSaveSource: (b: any) => save('/data-access/source', b),
  daDeleteSource: (id: number) => http.delete('/data-access/source', { params: { id } }).then((r) => r.data),
  daTestSource: (b: any) => http.post('/data-access/source/test', b).then((r) => r.data),
  daSourceTables: (id: number, schema?: string) => http.get('/data-access/source/tables', { params: { id, schema } }).then((r) => r.data),
  daSourceColumns: (id: number, table: string, schema?: string) => http.get('/data-access/source/columns', { params: { id, table, schema } }).then((r) => r.data),

  // ===== 文件管理 [SYS_ADMIN] =====
  daStores: () => http.get<FilestoreRow[]>('/data-access/file/store/list').then((r) => r.data),
  daSaveStore: (b: any) => save('/data-access/file/store', b),
  daDeleteStore: (id: number) => http.delete('/data-access/file/store', { params: { id } }).then((r) => r.data),
  daTestStore: (b: any) => http.post('/data-access/file/store/test', b).then((r) => r.data),
  daBrowse: (storeId: number, path: string) => http.get('/data-access/file/browse', { params: { storeId, path } }).then((r) => r.data),
  daMkdir: (storeId: number, path: string) => http.post('/data-access/file/mkdir', { storeId, path }).then((r) => r.data),
  daFileDelete: (storeId: number, path: string) => http.delete('/data-access/file/delete', { params: { storeId, path } }).then((r) => r.data),
  daFileCopy: (storeId: number, src: string, dst: string) => http.post('/data-access/file/copy', { storeId, src, dst }).then((r) => r.data),
  daFileUpload: (formData: FormData) => http.post('/data-access/file/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 }).then((r) => r.data),
  daIngestedFiles: () => http.get<IngestedFileRow[]>('/data-access/file/list-ingested').then((r) => r.data),
  daIngestFile: (b: any) => http.post('/data-access/file/ingest', b, { timeout: 120000 }).then((r) => r.data),

  // ===== 离线数据接入 [SYS_ADMIN] =====
  daOfflineJobs: () => http.get<OfflineJobRow[]>('/data-access/offline/job/list').then((r) => r.data),
  daSaveOfflineJob: (b: any) => save('/data-access/offline/job', b),
  daDeleteOfflineJob: (id: number) => http.delete('/data-access/offline/job', { params: { id } }).then((r) => r.data),
  daOfflinePreview: (b: any) => http.post('/data-access/offline/preview', b).then((r) => r.data),
  daOfflineRun: (jobId: number) => http.post('/data-access/offline/run', null, { params: { jobId }, timeout: 120000 }).then((r) => r.data),
  daOfflineRuns: (jobId: number) => http.get<OfflineRunRow[]>('/data-access/offline/run/list', { params: { jobId } }).then((r) => r.data),
  daOfflineTargetPreview: (targetDb: string, targetTable: string) => http.get('/data-access/offline/target/preview', { params: { targetDb, targetTable } }).then((r) => r.data),

  // ===== 实时数据接入 [SYS_ADMIN] =====
  daStreamJobs: () => http.get<StreamJobRow[]>('/data-access/stream/job/list').then((r) => r.data),
  daSaveStreamJob: (b: any) => save('/data-access/stream/job', b),
  daDeleteStreamJob: (id: number) => http.delete('/data-access/stream/job', { params: { id } }).then((r) => r.data),
  daStreamStart: (jobId: number) => http.post('/data-access/stream/start', null, { params: { jobId } }).then((r) => r.data),
  daStreamStop: (jobId: number) => http.post('/data-access/stream/stop', null, { params: { jobId } }).then((r) => r.data),
  daStreamStatus: (jobId: number) => http.get('/data-access/stream/status', { params: { jobId } }).then((r) => r.data),
  daStreamRuns: (jobId: number) => http.get<StreamRunRow[]>('/data-access/stream/run/list', { params: { jobId } }).then((r) => r.data),
  daRoutineLoads: () => http.get<RoutineLoadRow[]>('/data-access/stream/routine-load/list').then((r) => r.data),

  // ===== 数据探查 [SYS_ADMIN] =====
  daProfileJobs: () => http.get<ProfileJobRow[]>('/data-access/profile/job/list').then((r) => r.data),
  daProfileJobDetail: (id: number) => http.get<any>('/data-access/profile/job/detail', { params: { id } }).then((r) => r.data),
  daSaveProfileJob: (b: any) => save('/data-access/profile/job', b),
  daDeleteProfileJob: (id: number) => http.delete('/data-access/profile/job', { params: { id } }).then((r) => r.data),
  daProfileTables: (id: number) => http.get<any[]>('/data-access/profile/tables', { params: { id } }).then((r) => r.data),
  daProfileColumns: (id: number, table: string) => http.get<any[]>('/data-access/profile/columns', { params: { id, table } }).then((r) => r.data),
  daProfileRun: (jobId: number) => http.post('/data-access/profile/run', null, { params: { jobId }, timeout: 120000 }).then((r) => r.data),
  daProfileOnline: (jobId: number) => http.post('/data-access/profile/online', null, { params: { jobId } }).then((r) => r.data),
  daProfileOffline: (jobId: number) => http.post('/data-access/profile/offline', null, { params: { jobId } }).then((r) => r.data),
  daProfileRuns: (jobId?: number) => http.get<ProfileRunRow[]>('/data-access/profile/run/list', { params: jobId ? { jobId } : {} }).then((r) => r.data),
  daProfileRunDetail: (id: number) => http.get<ProfileRunRow>('/data-access/profile/run/detail', { params: { id } }).then((r) => r.data),
  daProfileRecords: (jobId: number) => http.get<ProfileSnapshotRow[]>('/data-access/profile/record/list', { params: { jobId } }).then((r) => r.data),
  daProfileDiffs: (jobId: number, tableName: string) => http.get<any[]>('/data-access/profile/diff/list', { params: { jobId, tableName } }).then((r) => r.data),
  daProfileCompare: (jobId: number, tableName: string, v1: number, v2: number) => http.get<any>('/data-access/profile/diff/compare', { params: { jobId, tableName, v1, v2 } }).then((r) => r.data),

  // ===== 数据治理 [SYS_ADMIN] =====
  // 数据标准
  govElements: () => http.get('/data-gov/std/element').then((r) => r.data),
  govSaveElement: (b: any) => save('/data-gov/std/element', b),
  govDeleteElement: (id: number) => http.delete('/data-gov/std/element', { params: { id } }).then((r) => r.data),
  govCodeSets: () => http.get('/data-gov/std/code-set').then((r) => r.data),
  govSaveCodeSet: (b: any) => save('/data-gov/std/code-set', b),
  govDeleteCodeSet: (id: number) => http.delete('/data-gov/std/code-set', { params: { id } }).then((r) => r.data),
  govCodeItems: (setId: number) => http.get('/data-gov/std/code-item', { params: { setId } }).then((r) => r.data),
  govSaveCodeItem: (b: any) => http.post('/data-gov/std/code-item', b).then((r) => r.data),
  govDeleteCodeItem: (id: number) => http.delete('/data-gov/std/code-item', { params: { id } }).then((r) => r.data),
  // 数据模型
  govModels: (domain?: string) => http.get('/data-gov/model/list', { params: domain ? { domain } : {} }).then((r) => r.data),
  govSaveModel: (b: any) => save('/data-gov/model', b),
  govDeleteModel: (id: number) => http.delete('/data-gov/model', { params: { id } }).then((r) => r.data),
  govModelTables: (modelId: number) => http.get('/data-gov/model/table', { params: { modelId } }).then((r) => r.data),
  govSaveModelTable: (b: any) => http.post('/data-gov/model/table', b).then((r) => r.data),
  govDeleteModelTable: (id: number) => http.delete('/data-gov/model/table', { params: { id } }).then((r) => r.data),
  govModelFields: (tableId: number) => http.get('/data-gov/model/field', { params: { tableId } }).then((r) => r.data),
  govSaveModelField: (b: any) => http.post('/data-gov/model/field', b).then((r) => r.data),
  govDeleteModelField: (id: number) => http.delete('/data-gov/model/field', { params: { id } }).then((r) => r.data),
  // 数据仓库
  govLayers: () => http.get('/data-gov/wh/layer').then((r) => r.data),
  govSaveLayer: (b: any) => save('/data-gov/wh/layer', b),
  govDeleteLayer: (code: string) => http.delete('/data-gov/wh/layer', { params: { code } }).then((r) => r.data),
  govLayerDs: (layerCode: string) => http.get('/data-gov/wh/layer/datasource', { params: { layerCode } }).then((r) => r.data),
  govBindLayerDs: (b: any) => http.post('/data-gov/wh/layer/datasource', b).then((r) => r.data),
  govUnbindLayerDs: (id: number) => http.delete('/data-gov/wh/layer/datasource', { params: { id } }).then((r) => r.data),
  govSubjects: () => http.get('/data-gov/wh/subject').then((r) => r.data),
  govSaveSubject: (b: any) => http.post('/data-gov/wh/subject', b).then((r) => r.data),
  govDeleteSubject: (id: number) => http.delete('/data-gov/wh/subject', { params: { id } }).then((r) => r.data),
  // 数据质量
  govRules: (dimension?: string) => http.get('/data-gov/quality/rule', { params: dimension ? { dimension } : {} }).then((r) => r.data),
  govSaveRule: (b: any) => save('/data-gov/quality/rule', b),
  govDeleteRule: (id: number) => http.delete('/data-gov/quality/rule', { params: { id } }).then((r) => r.data),
  govTasks: () => http.get('/data-gov/quality/task').then((r) => r.data),
  govSaveTask: (b: any) => http.post('/data-gov/quality/task', b).then((r) => r.data),
  govDeleteTask: (id: number) => http.delete('/data-gov/quality/task', { params: { id } }).then((r) => r.data),
  govRunQuality: (taskId: number) => http.post('/data-gov/quality/run', null, { params: { taskId }, timeout: 120000 }).then((r) => r.data),
  govQualityResult: (taskId: number) => http.get('/data-gov/quality/result', { params: { taskId } }).then((r) => r.data),
  // 元数据
  govMetaList: (params: { dsId?: number; kw?: string } = {}) => http.get('/data-gov/meta/list', { params }).then((r) => r.data),
  govMetaDetail: (id: number) => http.get('/data-gov/meta/detail', { params: { id } }).then((r) => r.data),
  govMetaSync: (dsId: number) => http.post('/data-gov/meta/sync', null, { params: { dsId }, timeout: 120000 }).then((r) => r.data),
  // 数据标签
  govTags: (category?: string) => http.get('/data-gov/tag/list', { params: category ? { category } : {} }).then((r) => r.data),
  govSaveTag: (b: any) => save('/data-gov/tag', b),
  govDeleteTag: (id: number) => http.delete('/data-gov/tag', { params: { id } }).then((r) => r.data),
  govTagRelations: (params: { targetTable?: string; targetColumn?: string } = {}) => http.get('/data-gov/tag/relation', { params }).then((r) => r.data),
  govBindTag: (b: any) => http.post('/data-gov/tag/relation', b).then((r) => r.data),
  govUnbindTag: (id: number) => http.delete('/data-gov/tag/relation', { params: { id } }).then((r) => r.data),
  // 主数据
  govMasters: () => http.get('/data-gov/master/list').then((r) => r.data),
  govSaveMaster: (b: any) => save('/data-gov/master', b),
  govDeleteMaster: (id: number) => http.delete('/data-gov/master', { params: { id } }).then((r) => r.data),
  govMasterRecords: (masterId: number) => http.get('/data-gov/master/record', { params: { masterId } }).then((r) => r.data),
  govSaveMasterRecord: (b: any) => http.post('/data-gov/master/record', b).then((r) => r.data),
  govDeleteMasterRecord: (id: number) => http.delete('/data-gov/master/record', { params: { id } }).then((r) => r.data),

  // ===== 数据开发 [SYS_ADMIN] =====
  // 函数管理
  devFunctions: () => http.get('/data-dev/func/list').then((r) => r.data),
  devSaveFunction: (b: any) => save('/data-dev/func', b),
  devDeleteFunction: (id: number) => http.delete('/data-dev/func', { params: { id } }).then((r) => r.data),
  // 数据开发（SQL 脚本）
  devScripts: () => http.get('/data-dev/script/list').then((r) => r.data),
  devSaveScript: (b: any) => save('/data-dev/script', b),
  devDeleteScript: (id: number) => http.delete('/data-dev/script', { params: { id } }).then((r) => r.data),
  devRunScript: (b: any) => http.post('/data-dev/script/run', b, { timeout: 120000 }).then((r) => r.data),
  devScriptRuns: (scriptId: number) => http.get('/data-dev/script/run-list', { params: { scriptId } }).then((r) => r.data),
  // 数据接出
  devExports: () => http.get('/data-dev/export/list').then((r) => r.data),
  devSaveExport: (b: any) => save('/data-dev/export', b),
  devDeleteExport: (id: number) => http.delete('/data-dev/export', { params: { id } }).then((r) => r.data),
  devRunExport: (id: number) => http.post('/data-dev/export/run', null, { params: { id }, timeout: 120000 }).then((r) => r.data),
  devExportRuns: (exportId: number) => http.get('/data-dev/export/run-list', { params: { exportId } }).then((r) => r.data),
  // 工作流
  devWorkflows: () => http.get('/data-dev/workflow/list').then((r) => r.data),
  devWorkflowDetail: (id: number) => http.get('/data-dev/workflow/detail', { params: { id } }).then((r) => r.data),
  devSaveWorkflow: (b: any) => save('/data-dev/workflow', b),
  devDeleteWorkflow: (id: number) => http.delete('/data-dev/workflow', { params: { id } }).then((r) => r.data),
  devRunWorkflow: (id: number) => http.post('/data-dev/workflow/run', null, { params: { id }, timeout: 180000 }).then((r) => r.data),
  devWorkflowRuns: (workflowId: number) => http.get('/data-dev/workflow/run-list', { params: { workflowId } }).then((r) => r.data),
  devOnlineWorkflow: (id: number) => http.post('/data-dev/workflow/online', null, { params: { id } }).then((r) => r.data),
  devOfflineWorkflow: (id: number) => http.post('/data-dev/workflow/offline', null, { params: { id } }).then((r) => r.data),

  // ===== 数据资产 [SYS_ADMIN] =====
  assetCatalogTree: () => http.get('/asset/catalog/tree').then((r) => r.data),
  assetSaveCatalog: (b: any) => save('/asset/catalog', b),
  assetDeleteCatalog: (id: number) => http.delete('/asset/catalog', { params: { id } }).then((r) => r.data),
  assetList: (params: { catalogId?: number; status?: string; kw?: string } = {}) => http.get('/asset/list', { params }).then((r) => r.data),
  assetSave: (b: any) => save('/asset', b),
  assetDelete: (id: number) => http.delete('/asset', { params: { id } }).then((r) => r.data),
  assetSubmit: (id: number) => http.post('/asset/submit', null, { params: { id } }).then((r) => r.data),
  assetApprove: (id: number, comment: string) => http.post('/asset/approve', { comment }, { params: { id } }).then((r) => r.data),
  assetReject: (id: number, comment: string) => http.post('/asset/reject', { comment }, { params: { id } }).then((r) => r.data),
  assetAudit: (assetId: number) => http.get('/asset/audit', { params: { assetId } }).then((r) => r.data),
  assetSourceTables: (dsId?: number) => http.get('/asset/source-tables', { params: dsId ? { dsId } : {} }).then((r) => r.data),
  assetSourceColumns: (metaTableId: number) => http.get('/asset/source-columns', { params: { metaTableId } }).then((r) => r.data),

  // ===== 运维中心 [SYS_ADMIN] =====
  opsQuery: (b: any) => http.post('/ops/query/run', b, { timeout: 120000 }).then((r) => r.data),
  opsOverview: () => http.get('/ops/overview').then((r) => r.data),
  opsTasks: () => http.get('/ops/tasks').then((r) => r.data),
  opsTaskStats: () => http.get('/ops/task-stats').then((r) => r.data),
  opsResource: () => http.get('/ops/resource').then((r) => r.data),
  opsCluster: () => http.get('/ops/cluster').then((r) => r.data),
  opsExecutors: () => http.get('/ops/executors').then((r) => r.data),
  opsConnectors: () => http.get('/ops/connectors').then((r) => r.data),

  // ===== 数据安全 [SYS_ADMIN] =====
  secStandards: () => http.get('/security/std/list').then((r) => r.data),
  secSaveStandard: (b: any) => save('/security/std', b),
  secDeleteStandard: (id: number) => http.delete('/security/std', { params: { id } }).then((r) => r.data),
  secMaskRules: () => http.get('/security/mask/rule').then((r) => r.data),
  secSaveMaskRule: (b: any) => save('/security/mask/rule', b),
  secDeleteMaskRule: (id: number) => http.delete('/security/mask/rule', { params: { id } }).then((r) => r.data),
  secMaskRels: () => http.get('/security/mask/rel').then((r) => r.data),
  secBindMask: (b: any) => http.post('/security/mask/rel', b).then((r) => r.data),
  secUnbindMask: (id: number) => http.delete('/security/mask/rel', { params: { id } }).then((r) => r.data),
  secKeys: () => http.get('/security/key/list').then((r) => r.data),
  secSaveKey: (b: any) => save('/security/key', b),
  secDeleteKey: (id: number) => http.delete('/security/key', { params: { id } }).then((r) => r.data),
  secAlertDefs: () => http.get('/security/alert/def').then((r) => r.data),
  secSaveAlertDef: (b: any) => save('/security/alert/def', b),
  secDeleteAlertDef: (id: number) => http.delete('/security/alert/def', { params: { id } }).then((r) => r.data),
  secAlertEvents: () => http.get('/security/alert/event').then((r) => r.data),
  secHandleAlert: (id: number) => http.post('/security/alert/handle', null, { params: { id } }).then((r) => r.data),
  secIpList: () => http.get('/security/ip/list').then((r) => r.data),
  secSaveIp: (b: any) => save('/security/ip', b),
  secDeleteIp: (id: number) => http.delete('/security/ip', { params: { id } }).then((r) => r.data),
  secSensitives: () => http.get('/security/sensitive/list').then((r) => r.data),
  secSaveSensitive: (b: any) => save('/security/sensitive', b),
  secDeleteSensitive: (id: number) => http.delete('/security/sensitive', { params: { id } }).then((r) => r.data),
  secDataPerms: () => http.get('/security/perm/list').then((r) => r.data),
  secSavePerm: (b: any) => save('/security/perm', b),
  secDeletePerm: (id: number) => http.delete('/security/perm', { params: { id } }).then((r) => r.data),

  // ===== 数据服务 [SYS_ADMIN] =====
  dsServices: () => http.get('/data-service/list').then((r) => r.data),
  dsSaveService: (b: any) => save('/data-service', b),
  dsDeleteService: (id: number) => http.delete('/data-service', { params: { id } }).then((r) => r.data),
  dsInvoke: (code: string, params: Record<string, any> = {}) => http.get(`/data-service/invoke/${code}`, { params }).then((r) => r.data),
  dsStats: () => http.get('/data-service/stats').then((r) => r.data),
  dsLogs: (serviceId?: number) => http.get('/data-service/log', { params: serviceId ? { serviceId } : {} }).then((r) => r.data),

  // ===== 数据集市（数据门户） =====
  marketResources: (type?: string) => http.get('/market/resources', { params: type ? { type } : {} }).then((r) => r.data),
  marketCart: () => http.get('/market/cart').then((r) => r.data),
  marketAddCart: (b: any) => http.post('/market/cart/add', b).then((r) => r.data),
  marketRemoveCart: (id: number) => http.delete('/market/cart/remove', { params: { id } }).then((r) => r.data),
  marketClearCart: () => http.post('/market/cart/clear', null).then((r) => r.data),
  marketPreviewTable: (b: any) => http.post('/market/preview-table', b, { timeout: 60000 }).then((r) => r.data),
  marketOverview: () => http.get('/market/overview').then((r) => r.data)
}
