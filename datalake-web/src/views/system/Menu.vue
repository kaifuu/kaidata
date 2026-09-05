<template>
  <div class="menu-page">
    <el-row :gutter="14" class="menu-body">
      <!-- 左：菜单树 -->
      <el-col :span="5">
        <div class="dl-card cat-card">
          <div class="card-head">
            <span class="card-head-title"><i class="cat-dot" />菜单树</span>
            <span class="count-badge">共 <b>{{ flat.length }}</b> 个</span>
          </div>
          <el-input v-model="treeKw" size="small" placeholder="筛选菜单…" clearable class="cat-search">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <div class="tree-scroll">
            <el-tree ref="treeRef" :data="treeData" :props="{ label: 'name', children: 'children' }" node-key="id"
                     highlight-current :expand-on-click-node="false" default-expand-all
                     :filter-node-method="treeFilter" @node-click="onNode">
              <template #default="{ data: n }">
                <div class="cat-node" :class="{ active: curId === n.id }">
                  <el-icon class="node-ic"><component :is="nodeIcon(n)" /></el-icon>
                  <span class="cat-name" :title="n.name">{{ n.name }}</span>
                  <span v-if="n.id !== 0 && childCount(n.id)" class="node-cnt">{{ childCount(n.id) }}</span>
                  <span class="cat-ops" v-if="n.id !== 0">
                    <el-icon class="op-edit" title="编辑" @click.stop="open(n)"><EditPen /></el-icon>
                    <el-icon class="op-del" title="删除" @click.stop="del(n)"><Delete /></el-icon>
                  </span>
                </div>
              </template>
            </el-tree>
            <div v-if="!flat.length" class="cat-empty hint">暂无菜单</div>
          </div>
          <div class="cat-foot hint">选中节点后右侧管理其子菜单</div>
        </div>
      </el-col>

      <!-- 右：菜单列表 -->
      <el-col :span="19">
        <div class="dl-card list-card">
          <div class="card-head">
            <span class="card-head-title"><el-icon class="title-icon head-ic"><MenuIcon /></el-icon>菜单列表</span>
            <span class="cur-cat">当前节点：<b>{{ curName }}</b></span>
            <span class="count-badge">共 <b>{{ rows.length }}</b> 个</span>
            <span class="role-tag">安全保密管理员</span>
          </div>
          <div class="dl-toolbar">
            <el-button type="primary" size="small" @click="open(null, curNode)">
              <el-icon><Plus /></el-icon>&nbsp;{{ curId === 0 ? '新增顶级菜单' : `在「${curName}」下新增` }}
            </el-button>
            <el-input v-model="kw" placeholder="名称 / 路径 / 权限 关键字" size="small" clearable style="width:220px">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <div class="toolbar-actions"><span class="muted">列表为选中节点的直接子级</span></div>
          </div>

          <el-table :data="paged" size="small" stripe border v-loading="loading">
            <el-table-column prop="name" label="名称" min-width="180">
              <template #default="{ row }">
                <span class="row-name">{{ row.name }}</span>
                <el-tag v-if="row.type === 'CATALOG' && childCount(row.id)" size="small" type="info" class="sub-tag">{{ childCount(row.id) }} 个子级</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="图标" width="140">
              <template #default="{ row }">
                <div class="ico-cell">
                  <el-icon class="ic"><component :is="row.icon || 'Menu'" /></el-icon>
                  <span class="muted">{{ row.icon || '—' }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="path" label="路径" width="150" />
            <el-table-column prop="perm" label="权限标识" width="120" />
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type === 'CATALOG' ? 'info' : 'success'">{{ row.type === 'CATALOG' ? '目录' : '菜单' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="76">
              <template #default="{ row }"><el-tag size="small" :type="row.status === 'DISABLED' ? 'info' : 'success'">{{ row.status === 'DISABLED' ? '停用' : '启用' }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="sort" label="排序" width="66" />
            <el-table-column label="操作" width="280" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button size="small" link type="success" @click="open(null, row)">新增下级</el-button>
                  <el-button size="small" link :type="row.status === 'DISABLED' ? 'success' : 'warning'" @click="toggle(row)">{{ row.status === 'DISABLED' ? '启用' : '停用' }}</el-button>
                  <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
                  <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <div class="table-empty">
                <el-icon class="empty-ic"><FolderOpened /></el-icon>
                <div>{{ kw ? '无匹配菜单' : (curId === 0 ? '暂无顶级菜单，点上方按钮新增' : '「' + curName + '」下暂无子级，点上方按钮新增') }}</div>
              </div>
            </template>
          </el-table>
          <div class="dl-pagination">
            <el-pagination :current-page="page.page" :page-size="page.size" :total="rows.length"
              :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
              @size-change="onSizeChange" @current-change="onPageChange" />
          </div>
        </div>
      </el-col>
    </el-row>

    <el-drawer v-model="dlg" :title="form.id ? '编辑菜单' : '新增菜单'" size="580px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="上级">
          <el-select v-model="form.parent_id" style="width:100%" clearable placeholder="顶级">
            <el-option v-for="m in parentOptions(form.id)" :key="m.id" :label="m.label" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type">
            <el-radio value="CATALOG">目录</el-radio>
            <el-radio value="MENU">菜单</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="图标">
          <el-popover :visible="pickerOpen" @update:visible="pickerOpen = $event" placement="bottom-start" :width="316" trigger="click" popper-class="icon-picker-pop">
            <template #reference>
              <div class="ico-trigger">
                <el-icon class="cur"><component :is="form.icon || 'Menu'" /></el-icon>
                <span class="nm">{{ form.icon || '选择图标' }}</span>
                <el-icon class="arr"><ArrowDown /></el-icon>
              </div>
            </template>
            <div class="picker">
              <el-input v-model="iconSearch" size="small" placeholder="搜索图标（如 user / data）" clearable :prefix-icon="Search" style="margin-bottom:8px" />
              <div class="grid">
                <div v-for="n in filteredIcons" :key="n" class="cell" :class="{ on: form.icon === n }" :title="n" @click="pickIcon(n)">
                  <el-icon><component :is="n" /></el-icon>
                </div>
              </div>
              <div v-if="!filteredIcons.length" class="empty">无匹配图标</div>
              <div class="hint">{{ iconSearch ? `匹配 ${filteredIcons.length} 个` : `共 ${iconNames.length} 个图标（输入关键字筛选）` }}</div>
            </div>
          </el-popover>
        </el-form-item>
        <el-form-item label="路径"><el-input v-model="form.path" placeholder="如 /system/user（目录留空）" /></el-form-item>
        <el-form-item label="权限标识"><el-input v-model="form.perm" placeholder="如 sys:user" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sort" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, ArrowDown, Search, Menu as MenuIcon, EditPen, Delete, FolderOpened, Folder } from '@element-plus/icons-vue'
import * as Icons from '@element-plus/icons-vue'
import { api, errMsg, type MenuRow } from '@/api'

// 全部 Element Plus 图标名（main.ts 已全局注册，<component :is="name"> 可直接用）
const iconNames = Object.keys(Icons).sort()
const iconSearch = ref('')
const pickerOpen = ref(false)
const filteredIcons = computed(() => {
  const kw = iconSearch.value.trim().toLowerCase()
  const all = kw ? iconNames.filter(n => n.toLowerCase().includes(kw)) : iconNames
  return all.slice(0, 84)
})
function pickIcon(n: string) { form.icon = n; pickerOpen.value = false; iconSearch.value = '' }

const flat = ref<MenuRow[]>([])
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const form = reactive<any>({ id: null, parent_id: null, name: '', icon: 'Menu', path: '', perm: '', type: 'MENU', sort: 99 })

// ---- 左树 ----
const treeRef = ref()
const treeKw = ref('')
const curId = ref(0)                       // 0 = 虚拟根「全部菜单」
const childrenOf = computed(() => {
  const m = new Map<number, MenuRow[]>()
  flat.value.forEach((r) => {
    const p = r.parent_id || 0
    if (!m.has(p)) m.set(p, [])
    m.get(p)!.push(r)
  })
  m.forEach((list) => list.sort((a, b) => (a.sort || 0) - (b.sort || 0) || a.id - b.id))
  return m
})
function childCount(id: number) { return childrenOf.value.get(id)?.length || 0 }
// 虚拟根 0 挂全部顶级，便于一棵树整体浏览
const treeData = computed(() => {
  const build = (id: number): any[] => (childrenOf.value.get(id) || []).map((r) => ({ ...r, children: build(r.id) }))
  return [{ id: 0, name: '全部菜单', virtual: true, children: build(0) }]
})
const curNode = computed(() => (curId.value === 0 ? { id: 0, name: '全部菜单' } : flat.value.find((r) => r.id === curId.value) || { id: 0, name: '全部菜单' }))
const curName = computed(() => curNode.value.name)
function nodeIcon(n: any) { return n.virtual ? 'Menu' : (n.type === 'CATALOG' ? Folder : (n.icon || 'Menu')) }
function treeFilter(value: string, data: any) { return !value || (data.name || '').includes(value) }
watch(treeKw, (v) => treeRef.value?.filter(v))
function onNode(n: any) { curId.value = n.id }

// ---- 右表：选中节点的直接子级 ----
const kw = ref('')
const rows = computed(() => {
  const list = childrenOf.value.get(curId.value) || []
  const k = kw.value.trim().toLowerCase()
  return k ? list.filter((r) => (r.name || '').toLowerCase().includes(k) || (r.path || '').toLowerCase().includes(k) || (r.perm || '').toLowerCase().includes(k)) : list
})

// ---- 客户端分页 ----
const page = reactive({ page: 1, size: 10 })
const paged = computed(() => rows.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
// 过滤条件 / 选中节点变化复位到第一页
watch([kw, curId], () => { page.page = 1 })

function parentOptions(excludeId: any) {
  const opts: { id: number; label: string }[] = []
  const walk = (nodes: any[], d: number) => nodes.forEach((n) => {
    if (n.id === 0) return
    opts.push({ id: n.id, label: '— '.repeat(d) + n.name })
    if (n.children?.length) walk(n.children, d + 1)
  })
  walk(treeData.value, 0)
  return excludeId ? opts.filter((o) => o.id !== Number(excludeId)) : opts
}

async function load() {
  loading.value = true
  try {
    // 接口返回嵌套树 → 压平成列表（childrenOf 按 parent_id 建索引；剥离 children 防 el-table 误渲染树形）
    const list: MenuRow[] = []
    const walk = (nodes: any[]) => nodes.forEach((n: any) => {
      const { children, ...rest } = n
      list.push(rest as MenuRow)
      if (children?.length) walk(children)
    })
    walk((await api.sysMenus()) || [])
    flat.value = list
    // 刷新后选中节点可能已被删除，兜底回根
    if (curId.value !== 0 && !flat.value.some((r) => r.id === curId.value)) curId.value = 0
  } catch (e) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function open(row?: MenuRow | null, parent?: any) {
  Object.assign(form, { id: null, parent_id: null, name: '', icon: 'Menu', path: '', perm: '', type: 'MENU', sort: 99 })
  if (row) Object.assign(form, { id: row.id, parent_id: row.parent_id, name: row.name, icon: row.icon || 'Menu', path: row.path, perm: row.perm, type: row.type, sort: row.sort })
  else if (parent && parent.id) form.parent_id = parent.id
  dlg.value = true
}
async function save() {
  if (!form.name) return ElMessage.warning('请输入名称')
  saving.value = true
  try {
    await api.sysSaveMenu({ ...form })
    ElMessage.success('保存成功'); dlg.value = false; await load()
    if (!form.id && form.parent_id) curId.value = Number(form.parent_id)   // 新增后跳到所属节点
  }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function del(row: MenuRow) {
  await ElMessageBox.confirm(`确定删除菜单「${row.name}」？`, '提示', { type: 'warning' })
  try { await api.sysDeleteMenu(row.id); ElMessage.success('已删除'); await load() } catch (e) { ElMessage.error(errMsg(e)) }
}
async function toggle(row: MenuRow) {
  try { const r: any = await api.sysToggleMenu(row.id); ElMessage.success(r.status === 'ENABLED' ? '已启用' : '已停用'); await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

onMounted(load)
</script>

<style scoped>
.menu-body { min-height: calc(100vh - 190px); }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.cat-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--tech-primary); box-shadow: var(--tech-glow); }
.cur-cat { font-size: 12px; color: var(--tech-text-muted); margin-left: auto; }
.cur-cat b { color: var(--tech-text); }
.card-head .count-badge { margin-left: 0; }
.card-head .role-tag { margin-left: 0; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }

/* 左树卡片 */
.cat-card { display: flex; flex-direction: column; padding: 12px; height: 100%; }
.cat-search { margin-bottom: 10px; }
.tree-scroll { flex: 1; overflow: auto; min-height: 120px; max-height: calc(100vh - 250px); padding-right: 2px; }
.cat-foot { margin-top: 10px; padding-top: 8px; border-top: 1px dashed var(--tech-panel-border); text-align: center; font-size: 12px; color: var(--tech-text-muted); }
.cat-empty { text-align: center; padding: 24px 0; }
.cat-node { display: flex; align-items: center; gap: 7px; width: 100%; padding: 5px 8px; border-radius: 6px; transition: background .15s; }
.cat-node:hover { background: var(--el-fill-color-light); }
.cat-node.active { background: color-mix(in srgb, var(--tech-primary) 12%, transparent); }
.node-ic { font-size: 14px; color: var(--tech-primary); flex-shrink: 0; }
.cat-name { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 13px; color: var(--tech-text); }
.cat-node.active .cat-name { color: var(--tech-primary); font-weight: 600; }
.node-cnt { font-size: 11px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 0 6px; flex-shrink: 0; }
.cat-ops { display: none; gap: 8px; flex-shrink: 0; }
.cat-node:hover .cat-ops { display: inline-flex; }
.cat-ops .el-icon { font-size: 13px; cursor: pointer; color: var(--tech-text-muted); transition: color .15s; }
.cat-ops .op-edit:hover { color: var(--tech-primary); }
.cat-ops .op-del:hover { color: var(--tech-danger); }
.cat-card :deep(.el-tree-node__content) { height: 32px; padding-right: 4px; }
.cat-card :deep(.el-tree-node__content:hover) { background: transparent; }
.cat-card :deep(.el-tree-node.is-current > .el-tree-node__content) { background: transparent; }

/* 右表卡片 */
.list-card { padding: 12px; }
.list-card .dl-toolbar { margin-bottom: 12px; }
.row-name { font-weight: 600; color: var(--tech-text); }
.sub-tag { margin-left: 8px; }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; color: var(--tech-text-muted); opacity: .6; }
.head-ic { font-size: 16px; color: var(--tech-primary); }

/* 表格图标列 */
.ico-cell { display: flex; align-items: center; gap: 8px; }
.ico-cell .ic { font-size: 16px; color: var(--tech-primary); }

/* 表单图标选择触发框 */
.ico-trigger {
  display: flex; align-items: center; gap: 8px;
  width: 100%; height: 32px; padding: 0 10px;
  border: 1px solid var(--tech-panel-border); border-radius: 6px;
  background: var(--el-fill-color-blank); color: var(--tech-text);
  cursor: pointer; transition: border-color .2s ease, box-shadow .2s ease;
}
.ico-trigger:hover { border-color: var(--tech-primary); }
.ico-trigger .cur { font-size: 18px; color: var(--tech-primary); }
.ico-trigger .nm { flex: 1; font-size: 13px; }
.ico-trigger .arr { font-size: 12px; color: var(--tech-text-muted); }
</style>

<style>
/* 图标选择器弹出层（全局，不受 scoped 限制） */
.icon-picker-pop .picker .grid {
  display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px;
  max-height: 240px; overflow-y: auto;
}
.icon-picker-pop .picker .cell {
  display: flex; align-items: center; justify-content: center;
  height: 38px; border-radius: 6px; cursor: pointer; font-size: 18px;
  color: var(--tech-text-muted); transition: all .15s ease;
}
.icon-picker-pop .picker .cell:hover { color: var(--tech-primary); background: color-mix(in srgb, var(--tech-primary) 12%, transparent); }
.icon-picker-pop .picker .cell.on { color: var(--tech-primary); background: color-mix(in srgb, var(--tech-primary) 18%, transparent); box-shadow: inset 0 0 0 1px var(--tech-primary); }
.icon-picker-pop .picker .empty { text-align: center; color: var(--tech-text-muted); font-size: 12px; padding: 16px 0; }
.icon-picker-pop .picker .hint { margin-top: 6px; font-size: 11px; color: var(--tech-text-muted); text-align: center; }
</style>
