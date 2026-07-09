<template>
  <div class="dl-card">
    <div class="card-title">
      <span class="ct-left"><el-icon class="title-icon"><OfficeBuilding /></el-icon>组织管理</span>
      <div class="head-right">
        <span class="count-badge">共 <b>{{ visibleCount }}</b> 个组织</span>
        <span class="role-tag">系统管理员</span>
        <el-button type="primary" size="small" :disabled="!tenantId" @click="open()"><el-icon><Plus /></el-icon> 新增组织</el-button>
      </div>
    </div>

    <!-- 检索工具条：租户切换 + 关键字过滤树 -->
    <div class="dl-toolbar">
      <el-select v-model="tenantId" size="small" placeholder="选择租户" style="width:180px" @change="onTenantChange">
        <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-input v-model="keyword" placeholder="名称 / 编码 关键字" size="small" clearable style="width:220px" />
      <div class="toolbar-actions">
        <span class="muted">输入关键字即时过滤组织树</span>
      </div>
    </div>

    <el-table :data="tree" row-key="id" :tree-props="{ children: 'children' }" size="small" stripe border
              default-expand-all v-loading="loading">
      <el-table-column prop="name" label="组织名称" min-width="220" />
      <el-table-column prop="code" label="编码" width="150" />
      <el-table-column prop="user_count" label="人数" width="80" />
      <el-table-column prop="sort" label="排序" width="80" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button size="small" link type="success" @click="open(null, row)">新增下级</el-button>
            <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
            <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑组织' : '新增组织'" width="440px">
      <el-form :model="form" label-width="72px">
        <el-form-item label="上级">
          <el-select v-model="form.parent_id" style="width:100%" clearable placeholder="顶级组织">
            <el-option v-for="o in parentOptions(form.id)" :key="o.id" :label="o.label" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
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
import { Plus, OfficeBuilding } from '@element-plus/icons-vue'
import { api, errMsg, type OrgRow, type TenantRow } from '@/api'

const tenants = ref<TenantRow[]>([])
const tenantId = ref<number | null>(null)
const flat = ref<OrgRow[]>([])
const keyword = ref('')
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const form = reactive<any>({ id: null, parent_id: null, code: '', name: '', sort: 1 })

// 按租户 + 关键字过滤 → 建树
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return flat.value.filter((o) =>
    o.tenant_id === tenantId.value &&
    (!kw || (o.name || '').toLowerCase().includes(kw) || (o.code || '').toLowerCase().includes(kw)))
})
const visibleCount = computed(() => filtered.value.length)
const tree = computed<OrgRow[]>(() => build(filtered.value))
function build(list: OrgRow[]): OrgRow[] {
  const map = new Map<number, OrgRow>()
  list.forEach((o) => map.set(o.id, { ...o, children: [] }))
  const roots: OrgRow[] = []
  map.forEach((o) => {
    if (o.parent_id && map.has(o.parent_id)) map.get(o.parent_id)!.children!.push(o)
    else roots.push(o)
  })
  return roots
}
// 父级下拉：排除自身及其子孙（避免成环）
function parentOptions(excludeId: any) {
  const opts: { id: number; label: string }[] = []
  const walk = (nodes: OrgRow[], depth: number) => nodes.forEach((n) => {
    opts.push({ id: n.id, label: '— '.repeat(depth) + n.name })
    if (n.children?.length) walk(n.children, depth + 1)
  })
  const filteredTree = excludeId ? removeSubtree(tree.value, Number(excludeId)) : tree.value
  walk(filteredTree, 0)
  return opts
}
function removeSubtree(nodes: OrgRow[], id: number): OrgRow[] {
  return nodes.filter((n) => n.id !== id).map((n) => ({ ...n, children: n.children ? removeSubtree(n.children, id) : [] }))
}

async function load() {
  if (!tenantId.value) return
  loading.value = true
  try { flat.value = await api.sysOrgOptions(tenantId.value!) } catch (e) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function onTenantChange() { keyword.value = ''; load() }

function open(row?: OrgRow | null, parent?: OrgRow) {
  Object.assign(form, { id: null, parent_id: null, code: '', name: '', sort: 1 })
  if (row) Object.assign(form, { id: row.id, parent_id: row.parent_id, code: row.code, name: row.name, sort: row.sort })
  else if (parent) form.parent_id = parent.id
  dlg.value = true
}
async function save() {
  if (!form.name) return ElMessage.warning('请输入名称')
  saving.value = true
  try {
    await api.sysSaveOrg({ ...form, tenant_id: tenantId.value })
    ElMessage.success('保存成功'); dlg.value = false; await load()
  } catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function del(row: OrgRow) {
  await ElMessageBox.confirm(`确定删除组织「${row.name}」？`, '提示', { type: 'warning' })
  try { await api.sysDeleteOrg(row.id); ElMessage.success('已删除'); await load() } catch (e) { ElMessage.error(errMsg(e)) }
}
onMounted(async () => {
  tenants.value = await api.sysTenantOptions()
  if (tenants.value[0]) { tenantId.value = tenants.value[0].id; await load() }
})
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.ct-left { display: inline-flex; align-items: center; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
</style>
