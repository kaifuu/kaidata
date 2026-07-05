<template>
  <div class="dl-card">
    <div class="card-title">
      <span>菜单管理</span>
      <div style="display:flex;align-items:center;gap:10px">
        <span class="role-tag">安全保密管理员</span>
        <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新增菜单</el-button>
      </div>
    </div>
    <el-table :data="tree" row-key="id" :tree-props="{ children: 'children' }" size="small" stripe border
              default-expand-all v-loading="loading">
      <el-table-column prop="name" label="名称" min-width="200" />
      <el-table-column label="图标" width="150">
        <template #default="{ row }">
          <div class="ico-cell">
            <el-icon class="ic"><component :is="row.icon || 'Menu'" /></el-icon>
            <span class="muted">{{ row.icon || '—' }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="path" label="路径" width="150" />
      <el-table-column prop="perm" label="权限标识" width="130" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.type === 'CATALOG' ? 'info' : 'success'">{{ row.type === 'CATALOG' ? '目录' : '菜单' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }"><el-tag size="small" :type="row.status === 'DISABLED' ? 'info' : 'success'">{{ row.status === 'DISABLED' ? '停用' : '启用' }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="70" />
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="success" @click="open(null, row)">新增下级</el-button>
          <el-button size="small" link :type="row.status === 'DISABLED' ? 'success' : 'warning'" @click="toggle(row)">{{ row.status === 'DISABLED' ? '启用' : '停用' }}</el-button>
          <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑菜单' : '新增菜单'" width="480px">
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
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, ArrowDown, Search } from '@element-plus/icons-vue'
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

const tree = computed(() => {
  const map = new Map<number, any>()
  flat.value.forEach((m) => map.set(m.id, { ...m, children: [] }))
  const roots: any[] = []
  map.forEach((n) => { n.parent_id && map.has(n.parent_id) ? map.get(n.parent_id).children.push(n) : roots.push(n) })
  return roots
})
function parentOptions(excludeId: any) {
  const opts: { id: number; label: string }[] = []
  const walk = (nodes: any[], d: number) => nodes.forEach((n) => {
    opts.push({ id: n.id, label: '— '.repeat(d) + n.name })
    if (n.children?.length) walk(n.children, d + 1)
  })
  const filtered = excludeId ? rm(tree.value, Number(excludeId)) : tree.value
  walk(filtered, 0)
  return opts
}
function rm(nodes: any[], id: number): any[] {
  return nodes.filter((n) => n.id !== id).map((n) => ({ ...n, children: n.children ? rm(n.children, id) : [] }))
}

async function load() { loading.value = true; try { flat.value = await api.sysMenus() } catch (e) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: MenuRow | null, parent?: MenuRow) {
  Object.assign(form, { id: null, parent_id: null, name: '', icon: 'Menu', path: '', perm: '', type: 'MENU', sort: 99 })
  if (row) Object.assign(form, { id: row.id, parent_id: row.parent_id, name: row.name, icon: row.icon || 'Menu', path: row.path, perm: row.perm, type: row.type, sort: row.sort })
  else if (parent) form.parent_id = parent.id
  dlg.value = true
}
async function save() {
  if (!form.name) return ElMessage.warning('请输入名称')
  saving.value = true
  try { await api.sysSaveMenu({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
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
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }

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
