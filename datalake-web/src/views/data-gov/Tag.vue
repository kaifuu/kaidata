<template>
  <div class="dl-card">
    <div class="card-title"><span>数据标签</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="标签定义" name="def">
        <el-button type="primary" size="small" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增标签</el-button>
        <el-table :data="tags" size="small" stripe border v-loading="loading">
          <el-table-column prop="name" label="名称" width="120" />
          <el-table-column prop="category" label="分类" width="100"><template #default="{ row }"><el-tag size="small" :color="row.color" effect="dark">{{ row.category }}</el-tag></template></el-table-column>
          <el-table-column prop="color" label="颜色" width="100" />
          <el-table-column prop="description" label="说明" min-width="180" />
          <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="打标关系" name="rel">
        <el-button type="primary" size="small" @click="relDlg = true" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 打标</el-button>
        <el-table :data="rels" size="small" stripe border v-loading="loadingRel">
          <el-table-column prop="tag_name" label="标签" width="120"><template #default="{ row }"><el-tag size="small" :color="row.color" effect="dark">{{ row.tag_name }}</el-tag></template></el-table-column>
          <el-table-column prop="target_type" label="对象" width="80" />
          <el-table-column label="目标" min-width="200"><template #default="{ row }">{{ row.target_db }}.{{ row.target_table }}{{ row.target_column ? '.' + row.target_column : '' }}</template></el-table-column>
          <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="danger" @click="unband(row)">移除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dlg" :title="form.id ? '编辑标签' : '新增标签'" width="440px">
      <el-form :model="form" label-width="60px" size="small">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="分类"><el-select v-model="form.category" style="width:100%"><el-option v-for="c in ['分类','级别','安全','业务']" :key="c" :label="c" :value="c" /></el-select></el-form-item>
        <el-form-item label="颜色"><el-input v-model="form.color" placeholder="#00e0ff" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="relDlg" title="打标" width="460px">
      <el-form :model="relForm" label-width="70px" size="small">
        <el-form-item label="标签"><el-select v-model="relForm.tag_id" style="width:100%"><el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" /></el-select></el-form-item>
        <el-form-item label="对象"><el-radio-group v-model="relForm.target_type"><el-radio value="table">表</el-radio><el-radio value="column">字段</el-radio></el-radio-group></el-form-item>
        <el-form-item label="库"><el-input v-model="relForm.target_db" placeholder="ods" /></el-form-item>
        <el-form-item label="表"><el-input v-model="relForm.target_table" placeholder="your_table" /></el-form-item>
        <el-form-item v-if="relForm.target_type === 'column'" label="字段"><el-input v-model="relForm.target_column" placeholder="batch_no" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="relDlg = false">取消</el-button><el-button type="primary" @click="bind">打标</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const tab = ref('def')
const tags = ref<any[]>([]); const loading = ref(false)
const rels = ref<any[]>([]); const loadingRel = ref(false)
const dlg = ref(false); const form = reactive<any>({ id: null, name: '', category: '分类', color: '#00e0ff', description: '' })
const relDlg = ref(false); const relForm = reactive<any>({ tag_id: null, target_type: 'table', target_db: 'ods', target_table: '', target_column: '' })

async function loadTags() { loading.value = true; try { tags.value = await api.govTags() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
async function loadRels() { loadingRel.value = true; try { rels.value = await api.govTagRelations() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingRel.value = false } }
function open(row?: any) { Object.assign(form, { id: null, name: '', category: '分类', color: '#00e0ff', description: '' }, row || {}); dlg.value = true }
async function save() { try { await api.govSaveTag({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await loadTags() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除标签 ${row.name}？`, '提示', { type: 'warning' }); try { await api.govDeleteTag(row.id); ElMessage.success('已删除'); await loadTags() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function bind() { if (!relForm.tag_id || !relForm.target_table) return ElMessage.warning('填标签与表'); try { await api.govBindTag({ ...relForm }); ElMessage.success('已打标'); relDlg.value = false; await loadRels() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function unband(row: any) { try { await api.govUnbindTag(row.id); await loadRels() } catch (e:any) { ElMessage.error(errMsg(e)) } }

onMounted(() => { loadTags(); loadRels() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
