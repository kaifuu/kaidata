<template>
  <div class="dl-card">
    <div class="card-title"><span>资产挂载</span><span class="role-tag">系统管理员</span></div>
    <el-form :model="form" label-width="100px" style="max-width:680px">
      <el-form-item label="所属目录">
        <el-select v-model="form.catalog_id" placeholder="选择资产目录节点" style="width:100%">
          <el-option v-for="c in catalogs" :key="c.id" :label="c.label" :value="c.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="资产名称"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="资产类型"><el-radio-group v-model="form.asset_type"><el-radio value="表">表资产</el-radio><el-radio value="字段">字段资产</el-radio></el-radio-group></el-form-item>
      <el-form-item label="挂载来源(表)">
        <el-select v-model="form.source_id" placeholder="从元数据选择表" filterable style="width:100%" @change="onTable">
          <el-option v-for="t in metaTables" :key="t.id" :label="`${t.schema_name}.${t.table_name}`" :value="t.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.asset_type === '字段'" label="挂载字段">
        <el-select v-model="form.source_col" placeholder="选字段" style="width:100%">
          <el-option v-for="c in columns" :key="c.name" :label="`${c.name} (${c.type})`" :value="c.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="归属人"><el-input v-model="form.owner" /></el-form-item>
      <el-form-item label="安全级别"><el-select v-model="form.security_level"><el-option v-for="s in ['公开','内部','敏感','机密']" :key="s" :label="s" :value="s" /></el-select></el-form-item>
      <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      <el-form-item><el-button type="primary" @click="save">挂载（保存为草稿）</el-button></el-form-item>
    </el-form>
    <div class="hint">挂载后资产为「草稿」状态，需到「资产审核」提交审核通过后正式生效。</div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'

const tree = ref<any[]>([])
const metaTables = ref<any[]>([]); const columns = ref<any[]>([])
const catalogs = computed(() => { const out: any[] = []; const walk = (ns: any[], d: number) => ns.forEach((n:any) => { out.push({ id: n.id, label: '— '.repeat(d) + n.name }); if (n.children?.length) walk(n.children, d + 1) }); walk(tree.value, 0); return out })
const form = reactive<any>({ catalog_id: null, name: '', asset_type: '表', source_type: 'meta_table', source_id: null, source_col: '', owner: '', security_level: '内部', description: '' })

async function onTable(id: number) {
  form.name = form.name || (metaTables.value.find((t:any) => t.id === id)?.table_name || '')
  try { columns.value = await api.assetSourceColumns(id) } catch { columns.value = [] }
}
async function save() {
  if (!form.catalog_id || !form.source_id || !form.name) return ElMessage.warning('填目录、来源表、名称')
  if (form.asset_type === '字段' && !form.source_col) return ElMessage.warning('选字段')
  const sourceType = form.asset_type === '字段' ? 'meta_column' : 'meta_table'
  try { await api.assetSave({ catalog_id: form.catalog_id, name: form.name, asset_type: form.asset_type, source_type: sourceType, source_id: form.source_id, owner: form.owner, security_level: form.security_level, description: form.description + (form.source_col ? ' [字段:' + form.source_col + ']' : '') }); ElMessage.success('已挂载（草稿）'); Object.assign(form, { name: '', source_id: null, source_col: '', description: '' }) }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
onMounted(async () => { try { tree.value = await api.assetCatalogTree() } catch { /* */ } try { metaTables.value = await api.assetSourceTables() } catch { /* */ } })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; }
</style>
