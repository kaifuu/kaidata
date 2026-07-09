<template>
  <div>
    <el-row :gutter="16">
      <!-- 角色列表 -->
      <el-col :span="9">
        <div class="dl-card">
          <div class="card-title">
            <span class="ct-left"><el-icon class="title-icon"><UserFilled /></el-icon>角色管理</span>
            <div class="head-right">
              <span class="count-badge">共 <b>{{ total }}</b> 个</span>
              <el-button type="primary" size="small" @click="openRole()"><el-icon><Plus /></el-icon> 新建</el-button>
            </div>
          </div>

          <div class="dl-toolbar" style="padding:8px 12px;margin-bottom:8px">
            <el-input v-model="keyword" placeholder="编码 / 名称" size="small" clearable style="width:160px" @keyup.enter="search" />
            <div class="toolbar-actions">
              <el-button size="small" type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
            </div>
          </div>

          <el-table :data="roles" size="small" stripe border highlight-current-row @current-change="pick" height="360">
            <el-table-column prop="code" label="角色码" width="130" />
            <el-table-column prop="name" label="名称" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click.stop="openRole(row)">编辑</el-button>
                <el-button size="small" link type="danger" :disabled="row.id <= 3" @click.stop="delRole(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="dl-pagination" style="margin-top:8px">
            <el-pagination :current-page="page.page" :page-size="page.size" :total="total"
              :page-sizes="[10, 20]" layout="total, prev, pager, next" small
              @size-change="onSizeChange" @current-change="onPageChange" />
          </div>
          <div class="hint">内置三员(id≤3)受保护，可调菜单权限、不可删/改成员。</div>
        </div>
      </el-col>

      <!-- 授权面板 -->
      <el-col :span="15">
        <div class="dl-card" v-if="cur">
          <div class="card-title"><span>授权：{{ cur.name }}（{{ cur.code }}）</span></div>

          <el-tabs v-model="tab">
            <!-- 菜单权限 -->
            <el-tab-pane label="菜单权限" name="menu">
              <el-tree
                :key="cur.id" ref="menuTreeRef" :data="menuTree" show-checkbox node-key="id"
                :default-checked-keys="cur.menu_ids" :props="{ label: 'name', children: 'children' }"
                default-expand-all />
              <div style="margin-top:12px">
                <el-button type="primary" size="small" :loading="savingMenu" @click="saveMenus">保存菜单权限</el-button>
                <span class="hint" style="margin-left:12px">勾选的菜单决定该角色侧栏可见性与接口权限</span>
              </div>
            </el-tab-pane>

            <!-- 成员用户 -->
            <el-tab-pane label="成员用户" name="user">
              <template v-if="cur.id <= 3">
                <el-alert type="warning" :closable="false" show-icon
                  title="内置三员角色的成员由系统维护，禁止在此调整。" />
              </template>
              <template v-else>
                <el-transfer
                  :key="'t' + cur.id" v-model="memberIds" :data="userTransfer" :titles="['全部用户', '本角色成员']"
                  filterable :props="{ key: 'key', label: 'label' }" />
                <div style="margin-top:12px">
                  <el-button type="primary" size="small" :loading="savingUser" @click="saveMembers">保存成员</el-button>
                  <span class="hint" style="margin-left:12px">角色授予：勾选用户即授予其该角色（用户登录后获得对应菜单）</span>
                </div>
              </template>
            </el-tab-pane>
          </el-tabs>
        </div>
        <div class="dl-card empty" v-else><el-icon><Pointer /></el-icon> 请在左侧选择一个角色进行授权</div>
      </el-col>
    </el-row>

    <el-dialog v-model="dlg" :title="form.id ? '编辑角色' : '新建角色'" width="400px">
      <el-form :model="form" label-width="72px">
        <el-form-item label="角色码"><el-input v-model="form.code" :disabled="!!form.id" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ElTree } from 'element-plus'
import { Plus, Pointer, Search, UserFilled } from '@element-plus/icons-vue'
import { api, errMsg, type RoleFullRow, type MenuRow, type UserRow } from '@/api'

const roles = ref<RoleFullRow[]>([])
const menus = ref<MenuRow[]>([])
const users = ref<UserRow[]>([])
const cur = ref<RoleFullRow | null>(null)
const tab = ref('menu')
const dlg = ref(false)
const saving = ref(false)
const savingMenu = ref(false)
const savingUser = ref(false)
const total = ref(0)
const page = reactive({ page: 1, size: 10 })
const keyword = ref('')
const form = reactive<any>({ id: null, code: '', name: '' })
const menuTreeRef = ref<InstanceType<typeof ElTree>>()
const memberIds = ref<number[]>([])

const menuTree = computed(() => {
  const map = new Map<number, any>()
  menus.value.forEach((m) => map.set(m.id, { ...m, children: [] }))
  const roots: any[] = []
  map.forEach((n) => { n.parent_id && map.has(n.parent_id) ? map.get(n.parent_id).children.push(n) : roots.push(n) })
  return roots
})
const userTransfer = computed(() => users.value.map((u) => ({ key: u.id, label: `${u.username}（${u.name}）` })))

async function load() {
  const [res, m, u] = await Promise.all([
    api.sysRoles({ page: page.page, size: page.size, keyword: keyword.value || undefined }),
    api.sysMenus(),
    api.sysUserOptions()
  ])
  roles.value = res.records
  total.value = res.total
  menus.value = m
  users.value = u
  if (!cur.value && roles.value[0]) pick(roles.value[0])
}
function search() { page.page = 1; load() }
function onPageChange(p: number) { page.page = p; load() }
function onSizeChange(s: number) { page.size = s; page.page = 1; load() }

function pick(row: RoleFullRow | null) {
  if (!row) return
  cur.value = row
  memberIds.value = [...(row.user_ids || [])]
  tab.value = 'menu'
}

function openRole(row?: RoleFullRow) {
  Object.assign(form, { id: null, code: '', name: '' })
  if (row) Object.assign(form, { id: row.id, code: row.code, name: row.name })
  dlg.value = true
}
async function saveRole() {
  if (!form.code || !form.name) return ElMessage.warning('请填写角色码与名称')
  saving.value = true
  try { await api.sysSaveRole({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function delRole(row: RoleFullRow) {
  await ElMessageBox.confirm(`确定删除角色「${row.name}」？`, '提示', { type: 'warning' })
  try { await api.sysDeleteRole(row.id); ElMessage.success('已删除'); if (cur.value?.id === row.id) cur.value = null; await load() }
  catch (e) { ElMessage.error(errMsg(e)) }
}

async function saveMenus() {
  if (!cur.value || !menuTreeRef.value) return
  savingMenu.value = true
  const ids = [...menuTreeRef.value.getCheckedKeys(), ...menuTreeRef.value.getHalfCheckedKeys()] as number[]
  try {
    await api.sysGrantMenus(cur.value.id, ids)
    ElMessage.success('菜单权限已保存'); await load()
  } catch (e) { ElMessage.error(errMsg(e)) } finally { savingMenu.value = false }
}
async function saveMembers() {
  if (!cur.value) return
  savingUser.value = true
  try {
    await api.sysGrantUsers(cur.value.id, memberIds.value)
    ElMessage.success('成员已保存'); await load()
  } catch (e) { ElMessage.error(errMsg(e)) } finally { savingUser.value = false }
}

onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.ct-left { display: inline-flex; align-items: center; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { color: var(--tech-text-muted); font-size: 12px; }
.empty { text-align: center; color: var(--tech-text-muted); padding: 60px 0; display: flex; flex-direction: column; align-items: center; gap: 10px; }
</style>
