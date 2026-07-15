// DAG 算子图标 —— 零依赖内联 SVG path 注册表。
// 所有图标 viewBox="0 0 24 24"、stroke="currentColor"、fill="none"，随父级 color 着色（随 category/group/主题切换）。
// 解析优先级：显式 svg-key icon > KIND_ICON[kind] > GROUP_DEFAULT_ICON[group] > generic。

export type IconKey = string

/** 每个图标 = 一组 <path d>，渲染时 v-for */
export const ICONS: Record<IconKey, string[]> = {
  // —— 8 个 group 默认图标 ——
  'g-input':     ['M4 6h16v12H4z', 'M4 10h16', 'M4 14h16'],                                   // 数据库表（输入源）
  'g-transform': ['M4 8h12', 'M16 5l3 3-3 3', 'M20 16H8', 'M8 13l-3 3 3 3'],                   // 双向转换（列变换）
  'g-cleanse':   ['M12 3l8 3v5c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6z', 'm8.5 11 2.5 2.5 4.5-4.5'], // 盾牌+勾（质量校验）
  'g-mask':      ['M6 10V8a6 6 0 0 1 12 0v2', 'M5 10h14v10H5z', 'M5 14h14'],                   // 锁（脱敏/加密）
  'g-query':     ['M11 5a6 6 0 1 0 0 12 6 6 0 0 0 0-12z', 'm14.5 14.5 4 4'],                   // 放大镜（查询/查找）
  'g-stats':     ['M6 20v-8', 'M11 20V6', 'M16 20v-5', 'M4 20h16'],                            // 柱状图（统计）
  'g-flow':      ['M12 3l9 9-9 9-9-9z'],                                                        // 菱形（流程分支/决策）
  'g-output':    ['M4 6h16v12H4z', 'M4 10h16', 'm3.5 4.5 2 2 4-4'],                            // 表+勾（写出目标）
  // —— kind 细化图标（覆盖 group 默认，提升辨识度）——
  'kafka':       ['M4 6h16v12H4z', 'M4 6l8 6 8-6'],                                             // 消息信封（Kafka 消息）
  'filter':      ['M4 5h16l-6 7v6l-4-2v-4z'],                                                   // 漏斗（过滤）
  'calc':        ['M7 5c-1.5 0-1.5 14 0 14', 'M17 5c1.5 0 1.5 14 0 14', 'M9 12h6'],            // ƒ（计算/UDF）
  'join':        ['M9 12a4 4 0 1 0 0-.01', 'M15 12a4 4 0 1 0 0-.01'],                           // 交叠圆（连接）
  'code':        ['m9 8-4 4 4 4', 'm15 8 4 4-4 4'],                                             // </>（JS/Java/SQL 代码）
  'sort':        ['M11 4v16', 'M7 8l4-4 4 4', 'M7 16l4 4 4-4'],                                 // 升降箭头（排序）
  'dedup':       ['M5 5h14v14H5z', 'M9 10h6', 'M9 14h6'],                                       // 方块+等号（去重/唯一）
  'rest':        ['M7 18a4 4 0 0 1-.5-8 5 5 0 0 1 9.5 1', 'm13 13 4 4'],                       // 云+链路（REST）
  'generic':     ['M5 5h14v14H5z', 'M9 9h6v6H9z'],                                             // 兜底方块
}

/** group → 默认 icon key */
export const GROUP_DEFAULT_ICON: Record<string, IconKey> = {
  input: 'g-input', transform: 'g-transform', cleanse: 'g-cleanse', mask: 'g-mask',
  query: 'g-query', stats: 'g-stats', flow: 'g-flow', output: 'g-output',
}

/** kind → 细化 icon key（优先于 group 默认） */
export const KIND_ICON: Record<string, IconKey> = {
  // transform 类
  select: 'g-transform', value_map: 'g-transform', filter: 'filter', dedup: 'dedup', calc: 'calc', sort: 'sort', udf: 'calc',
  string_replace: 'g-transform', string_ops: 'g-transform', split_field: 'g-transform', string_to_date: 'g-transform',
  join: 'join', js_code: 'code', java_code: 'code', exec_sql: 'code',
  // cleanse 类
  num_range: 'g-cleanse', null_check: 'g-cleanse', dup_check: 'g-cleanse', url_check: 'g-cleanse',
  id_check: 'g-cleanse', regex_check: 'g-cleanse', data_validate: 'g-cleanse',
  // mask 类
  mask_partial: 'g-mask', mask_delete: 'g-mask', mask_random: 'g-mask', encrypt: 'g-mask',
  // query 类
  rest_client: 'rest', stream_lookup: 'g-query', rest_input: 'rest',
  // stats 类
  aggregate: 'g-stats', univariate: 'g-stats', sampling: 'g-stats',
  // flow 类
  switch_case: 'g-flow',
  // input 类
  table: 'g-input', table_input: 'g-input', kafka: 'kafka', csv_input: 'g-input', excel_input: 'g-input',
  json_input: 'g-input', generate_rows: 'g-input', xml_input: 'g-input', text_input: 'g-input',
  // output 类
  table_output: 'g-output', kafka_output: 'g-output', excel_output: 'g-output', json_output: 'g-output', insert_update: 'g-output',
}

/** 非空且在注册表内 → 是 svg-key */
export function isSvgKey(v: string | undefined): boolean {
  return !!v && v in ICONS
}

/** 解析图标 key：svg-key icon > KIND_ICON[kind] > group 默认 > generic */
export function resolveIcon(icon?: string, group?: string, kind?: string): IconKey {
  if (isSvgKey(icon)) return icon as IconKey
  if (kind && KIND_ICON[kind]) return KIND_ICON[kind]
  if (group && GROUP_DEFAULT_ICON[group]) return GROUP_DEFAULT_ICON[group]
  return 'generic'
}
