<template>
  <div class="dl-card">
    <div class="card-title"><span>数据标准</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="数据元" name="element">
        <el-button type="primary" size="small" @click="openEl()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增数据元</el-button>
        <el-table :data="elements" size="small" stripe border v-loading="loading">
          <el-table-column prop="code" label="编码" width="140" />
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column prop="data_type" label="类型" width="90" />
          <el-table-column label="长度/精度" width="100"><template #default="{ row }">{{ row.length }} / {{ row.precision_ }},{{ row.scale_ }}</template></el-table-column>
          <el-table-column prop="definition" label="定义" min-width="160" show-overflow-tooltip />
          <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="openEl(row)">编辑</el-button><el-button link size="small" type="danger" @click="delEl(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="代码集" name="code">
        <el-button type="primary" size="small" @click="openCs()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增代码集</el-button>
        <el-table :data="codeSets" size="small" stripe border v-loading="loadingCs">
          <el-table-column prop="code" label="编码" width="140" />
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="200"><template #default="{ row }"><el-button link size="small" type="primary" @click="openItems(row)">代码项</el-button><el-button link size="small" type="primary" @click="openCs(row)">编辑</el-button><el-button link size="small" type="danger" @click="delCs(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="elDlg" :title="elForm.id ? '编辑数据元' : '新增数据元'" width="500px">
      <el-form :model="elForm" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="elForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="elForm.name" /></el-form-item>
        <el-form-item label="类型"><el-input v-model="elForm.data_type" placeholder="VARCHAR/INT/DATETIME..." /></el-form-item>
        <el-form-item label="长度/精度"><el-input-number v-model="elForm.length" :min="0" controls-position="right" style="width:110px" /> / <el-input-number v-model="elForm.precision_" :min="0" controls-position="right" style="width:90px" />,<el-input-number v-model="elForm.scale_" :min="0" controls-position="right" style="width:90px" /></el-form-item>
        <el-form-item label="定义"><el-input v-model="elForm.definition" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="elDlg = false">取消</el-button><el-button type="primary" @click="saveEl">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="csDlg" :title="csForm.id ? '编辑代码集' : '新增代码集'" width="440px">
      <el-form :model="csForm" label-width="60px" size="small">
        <el-form-item label="编码"><el-input v-model="csForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="csForm.name" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="csForm.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="csDlg = false">取消</el-button><el-button type="primary" @click="saveCs">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="itemDlg" :title="`代码项 - ${curSet?.name || ''}`" width="540px">
      <div style="margin-bottom:8px;display:flex;gap:6px"><el-input v-model="newItem.code" size="small" placeholder="编码" style="width:120px" /><el-input v-model="newItem.name" size="small" placeholder="名称" style="width:160px" /><el-input-number v-model="newItem.sort" :min="0" size="small" style="width:100px" /><el-button size="small" type="primary" @click="addItem">添加</el-button></div>
      <el-table :data="codeItems" size="small" border max-height="300">
        <el-table-column prop="code" label="编码" width="140" /><el-table-column prop="name" label="名称" /><el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="danger" @click="delItem(row)">删除</el-button></template></el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const tab = ref('element')
const elements = ref<any[]>([]); const loading = ref(false)
const codeSets = ref<any[]>([]); const loadingCs = ref(false)
const elDlg = ref(false); const elForm = reactive<any>({ id: null, code: '', name: '', data_type: 'VARCHAR', length: 64, precision_: 0, scale_: 0, definition: '' })
const csDlg = ref(false); const csForm = reactive<any>({ id: null, code: '', name: '', description: '' })
const itemDlg = ref(false); const curSet = ref<any>(null); const codeItems = ref<any[]>([]); const newItem = reactive<any>({ code: '', name: '', sort: 1 })

async function loadEl() { loading.value = true; try { elements.value = await api.govElements() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
async function loadCs() { loadingCs.value = true; try { codeSets.value = await api.govCodeSets() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingCs.value = false } }
function openEl(row?: any) { Object.assign(elForm, { id: null, code: '', name: '', data_type: 'VARCHAR', length: 64, precision_: 0, scale_: 0, definition: '' }, row || {}); elDlg.value = true }
async function saveEl() { try { await api.govSaveElement({ ...elForm }); ElMessage.success('保存成功'); elDlg.value = false; await loadEl() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delEl(row: any) { await ElMessageBox.confirm(`删除数据元 ${row.code}？`, '提示', { type: 'warning' }); try { await api.govDeleteElement(row.id); ElMessage.success('已删除'); await loadEl() } catch (e:any) { ElMessage.error(errMsg(e)) } }
function openCs(row?: any) { Object.assign(csForm, { id: null, code: '', name: '', description: '' }, row || {}); csDlg.value = true }
async function saveCs() { try { await api.govSaveCodeSet({ ...csForm }); ElMessage.success('保存成功'); csDlg.value = false; await loadCs() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delCs(row: any) { await ElMessageBox.confirm(`删除代码集 ${row.code}？`, '提示', { type: 'warning' }); try { await api.govDeleteCodeSet(row.id); ElMessage.success('已删除'); await loadCs() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openItems(row: any) { curSet.value = row; itemDlg.value = true; Object.assign(newItem, { code: '', name: '', sort: 1 }); try { codeItems.value = await api.govCodeItems(row.id) } catch { codeItems.value = [] } }
async function addItem() { if (!newItem.code || !newItem.name) return ElMessage.warning('填编码与名称'); try { await api.govSaveCodeItem({ set_id: curSet.value.id, ...newItem }); Object.assign(newItem, { code: '', name: '', sort: 1 }); codeItems.value = await api.govCodeItems(curSet.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delItem(row: any) { try { await api.govDeleteCodeItem(row.id); codeItems.value = await api.govCodeItems(curSet.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }

onMounted(() => { loadEl(); loadCs() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
