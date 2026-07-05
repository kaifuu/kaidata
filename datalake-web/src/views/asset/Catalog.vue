<template>
  <div class="dl-card">
    <div class="card-title"><span>资产编目</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="资产目录" name="tree">
        <el-button size="small" type="primary" @click="openNode()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增节点</el-button>
        <el-table :data="tree" row-key="id" :tree-props="{ children: 'children' }" size="small" border default-expand-all>
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column prop="code" label="编码" width="140" />
          <el-table-column prop="node_type" label="类型" width="100" />
          <el-table-column label="操作" width="200"><template #default="{ row }"><el-button link size="small" type="success" @click="openNode(null, row)">新增下级</el-button><el-button link size="small" type="primary" @click="openNode(row)">编辑</el-button><el-button link size="small" type="danger" @click="delNode(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="资产浏览" name="asset">
        <div style="margin-bottom:8px;display:flex;gap:8px">
          <el-select v-model="fCatalog" placeholder="按目录筛选" clearable size="small" style="width:220px" @change="loadAssets"><el-option v-for="c in flatNodes" :key="c.id" :label="c.label" :value="c.id" /></el-select>
          <el-select v-model="fStatus" placeholder="状态" clearable size="small" style="width:140px" @change="loadAssets"><el-option v-for="s in ['草稿','待审','通过','驳回']" :key="s" :label="s" :value="s" /></el-select>
          <el-input v-model="fKw" placeholder="资产名/说明" size="small" style="width:200px" clearable @change="loadAssets" />
        </div>
        <el-table :data="assets" size="small" stripe border v-loading="loadingA">
          <el-table-column prop="name" label="资产名" min-width="140" />
          <el-table-column prop="asset_type" label="类型" width="80" />
          <el-table-column prop="catalog_name" label="目录" width="120" />
          <el-table-column label="来源" min-width="160"><template #default="{ row }">{{ row.source_type }} #{{ row.source_id }}</template></el-table-column>
          <el-table-column prop="security_level" label="安全级别" width="90" />
          <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="stType(row.status)">{{ row.status }}</el-tag></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="nodeDlg" :title="nodeForm.id ? '编辑节点' : '新增节点'" width="440px">
      <el-form :model="nodeForm" label-width="70px" size="small">
        <el-form-item label="上级"><el-select v-model="nodeForm.parent_id" clearable placeholder="顶级" style="width:100%"><el-option v-for="c in parentOpts" :key="c.id" :label="c.label" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="编码"><el-input v-model="nodeForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="nodeForm.name" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="nodeForm.node_type" style="width:100%"><el-option v-for="t in ['业务域','主题','分类']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="nodeDlg = false">取消</el-button><el-button type="primary" @click="saveNode">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const tab = ref('tree')
const tree = ref<any[]>([])
const nodeDlg = ref(false)
const nodeForm = reactive<any>({ id: null, code: '', name: '', parent_id: null, node_type: '分类', sort: 1 })
const flatNodes = computed(() => { const out: any[] = []; const walk = (ns: any[], d: number) => ns.forEach((n) => { out.push({ id: n.id, label: '— '.repeat(d) + n.name }); if (n.children?.length) walk(n.children, d + 1) }); walk(tree.value, 0); return out })
const parentOpts = computed(() => flatNodes.value)
const assets = ref<any[]>([]); const loadingA = ref(false)
const fCatalog = ref<number | null>(null); const fStatus = ref(''); const fKw = ref('')
const stType = (s: string): any => ({ 通过: 'success', 待审: 'warning', 驳回: 'danger', 草稿: 'info' } as any)[s] || ''
async function loadTree() { try { tree.value = await api.assetCatalogTree() } catch (e:any) { ElMessage.error(errMsg(e)) } }
function openNode(row?: any, parent?: any) { Object.assign(nodeForm, { id: null, code: '', name: '', parent_id: null, node_type: '分类', sort: 1 }, row ? { ...row } : parent ? { parent_id: parent.id } : {}); nodeDlg.value = true }
async function saveNode() { if (!nodeForm.name) return ElMessage.warning('填名称'); try { await api.assetSaveCatalog({ ...nodeForm }); ElMessage.success('保存成功'); nodeDlg.value = false; await loadTree() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delNode(row: any) { await ElMessageBox.confirm(`删除节点 ${row.name}？`, '提示', { type: 'warning' }); try { await api.assetDeleteCatalog(row.id); ElMessage.success('已删除'); await loadTree() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function loadAssets() { loadingA.value = true; try { assets.value = await api.assetList({ catalogId: fCatalog.value || undefined, status: fStatus.value || undefined, kw: fKw.value || undefined }) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingA.value = false } }
onMounted(() => { loadTree(); loadAssets() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
